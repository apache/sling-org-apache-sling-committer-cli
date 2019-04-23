/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.cli.impl.jira;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.junit.rules.ExternalResource;

import com.google.gson.Gson;
import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;

public class MockJira extends ExternalResource {
    
    private static final Pattern VERSION_RELATED_ISSUES = Pattern.compile("^/jira/rest/api/2/version/(\\d+)/relatedIssueCounts$");
    
    static final String AUTH_USER = "jira-user";
    static final String AUTH_PWD = "jira-password";
    
    public static void main(String[] args) throws Throwable {
        
        MockJira mj = new MockJira();
        mj.before();
        System.out.println(mj.getBoundPort());
    }

    private HttpServer server;
    
    @Override
    protected void before() throws Throwable {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        HttpContext rootContext = server.createContext("/");
        rootContext.setAuthenticator(new BasicAuthenticator(getClass().getSimpleName()) {
            
            @Override
            public boolean checkCredentials(String username, String password) {
                return AUTH_USER.equals(username) && AUTH_PWD.equals(password);
            }
            
            @Override
            public Result authenticate(HttpExchange t) {
                if ( t.getRequestMethod().contentEquals("GET") )
                        return new Authenticator.Success(new HttpPrincipal("anonymous", getClass().getSimpleName()));
                return super.authenticate(t);
            }
        });
        rootContext.setHandler(httpExchange -> {
            
            switch ( httpExchange.getRequestMethod() ) {
            case "GET":
                if ( httpExchange.getRequestURI().getPath().equals("/jira/rest/api/2/project/SLING/versions") ) {
                    httpExchange.sendResponseHeaders(200, 0);
                    try ( InputStream in = getClass().getResourceAsStream("/jira/versions.json");
                            OutputStream out = httpExchange.getResponseBody() ) {
                        IOUtils.copy(in, out);
                    }
                } else {
                    Matcher matcher = VERSION_RELATED_ISSUES.matcher(httpExchange.getRequestURI().getPath());
                    if ( matcher.matches() ) {
                        int version = Integer.parseInt(matcher.group(1));
                        InputStream in = getClass().getResourceAsStream("/jira/relatedIssueCounts/" + version + ".json");
                        if ( in == null  ) {
                            httpExchange.sendResponseHeaders(404, -1);
                        } else {
                            httpExchange.sendResponseHeaders(200, 0);
                            try ( OutputStream out = httpExchange.getResponseBody() ) {
                                IOUtils.copy(in, out);
                            }
                        }
                    } else {
                        httpExchange.sendResponseHeaders(400, -1);
                    }
                }
                break;
            case "POST":
                if ( httpExchange.getRequestURI().getPath().equals("/jira/rest/api/2/version") ) {
                    handleCreateVersion(httpExchange);
                    return;
                }
                httpExchange.sendResponseHeaders(400, -1);
                break;
                default:
                    httpExchange.sendResponseHeaders(400, -1);
            }
            
        });
        
        server.start();
    }

    private void handleCreateVersion(HttpExchange httpExchange) throws IOException {
        Gson gson = new Gson();
        try ( InputStreamReader reader = new InputStreamReader(httpExchange.getRequestBody())) {
            VersionToCreate version = gson.fromJson(reader, VersionToCreate.class);
            if ( version.getName() == null || version.getName().isEmpty() ) {
                error(httpExchange, gson, 
                        er -> er.getErrors().put("name", "You must specify a valid version name"));
                return;
            }
            
            if ( !"SLING".equals(version.getProject()) ) {
                error(httpExchange, gson, 
                        er -> er.getErrorMessages().add("Project must be specified to create a version."));
                return;
            }
            
            // note not all fields are sent, projectId and self are missing
            CreatedVersion createdVersion = new CreatedVersion();
            createdVersion.archived = false;
            createdVersion.id = ThreadLocalRandom.current().nextInt();
            createdVersion.released = false;
            createdVersion.name = version.getName();
            
            try ( OutputStreamWriter out = new OutputStreamWriter(httpExchange.getResponseBody()) ) {
                httpExchange.sendResponseHeaders(201, 0);
                gson.toJson(createdVersion, out);
            }
        }
    }
    
    private void error(HttpExchange httpExchange, Gson gson, Consumer<ErrorResponse> c) throws IOException {
        try ( OutputStreamWriter out = new OutputStreamWriter(httpExchange.getResponseBody()) ) {
            httpExchange.sendResponseHeaders(400, 0);
            ErrorResponse er = new ErrorResponse();
            c.accept(er);
            gson.toJson(er, out);
        }
    }
    
    @Override
    protected void after() {
        
        server.stop(0);
    }
    
    public int getBoundPort() {
        
        return server.getAddress().getPort();
    }
    
    static class VersionToCreate {
        private String name;
        private String project;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getProject() {
            return project;
        }

        public void setProject(String project) {
            this.project = project;
        }
    }
    
    static class CreatedVersion {
        private String name;
        private int id;
        private boolean archived;
        private boolean released;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public boolean isArchived() {
            return archived;
        }

        public void setArchived(boolean archived) {
            this.archived = archived;
        }

        public boolean isReleased() {
            return released;
        }

        public void setReleased(boolean released) {
            this.released = released;
        }
    }
}
