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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.junit.rules.ExternalResource;

import com.sun.net.httpserver.HttpServer;

public class MockJira extends ExternalResource {
    
    private static final Pattern VERSION_RELATED_ISSUES = Pattern.compile("^/jira/rest/api/2/version/(\\d+)/relatedIssueCounts$");
    
    public static void main(String[] args) throws Throwable {
        
        MockJira mj = new MockJira();
        mj.before();
        System.out.println(mj.getBoundPort());
    }

    private HttpServer server;
    
    @Override
    protected void before() throws Throwable {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", httpExchange -> {
            
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
        });
        
        server.start();
    }
    
    @Override
    protected void after() {
        
        server.stop(0);
    }
    
    public int getBoundPort() {
        
        return server.getAddress().getPort();
    }
}
