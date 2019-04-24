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
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.rules.ExternalResource;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;

public class MockJira extends ExternalResource {
    
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
        
        List<JiraAction> actions = new ArrayList<>();
        actions.add(new ListVersionsJiraAction());
        actions.add(new GetRelatedIssueCountsForVersionsJiraAction());
        actions.add(new CreateVersionJiraAction());
        
        // fallback, always executed
        actions.add(new JiraAction() {
            @Override
            public boolean tryHandle(HttpExchange ex) throws IOException {
                ex.sendResponseHeaders(400, -1);
                return true;
            }
        });
        
        rootContext.setHandler(httpExchange -> {
            
            for ( JiraAction action : actions ) {
                if ( action.tryHandle(httpExchange) ) {
                    break;
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
