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
import java.util.List;

import org.apache.commons.io.Charsets;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;

public class IssuesSearchJiraAction implements JiraAction {
    
    static final String KNOWN_JQL_QUERY = "project = SLING AND resolution = Unresolved AND fixVersion = \"Committer CLI 1.0.0\""; 

    @Override
    public boolean tryHandle(HttpExchange ex) throws IOException {
        if ( !ex.getRequestMethod().equals("GET") || 
               !ex.getRequestURI().getPath().equals("/jira/rest/api/2/search") ) {
            return false;
        }
        
        List<NameValuePair> parsed = URLEncodedUtils.parse(ex.getRequestURI(), Charsets.UTF_8);
        
        for ( NameValuePair pair : parsed ) {
            if ( "jql".equals(pair.getName()) && KNOWN_JQL_QUERY.equals(pair.getValue()) ) {
                serveFileFromClasspath(ex, "/jira/search/unresolved-committer-cli-1.0.0.json");
                return true;
            }
        }
        error(ex, new Gson(), er -> er.getErrorMessages().add("Unable to run unknown JQL query, only available one is [" + KNOWN_JQL_QUERY +"]"));
        
        return true;
    }

}
