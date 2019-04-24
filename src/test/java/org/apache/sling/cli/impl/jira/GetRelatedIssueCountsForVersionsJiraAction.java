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
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;

import com.sun.net.httpserver.HttpExchange;

public class GetRelatedIssueCountsForVersionsJiraAction implements JiraAction {

    private static final Pattern VERSION_RELATED_ISSUES = Pattern.compile("^/jira/rest/api/2/version/(\\d+)/relatedIssueCounts$");

    @Override
    public boolean tryHandle(HttpExchange ex) throws IOException {
        if ( !ex.getRequestMethod().equals("GET") )
            return false;

        Matcher matcher = VERSION_RELATED_ISSUES.matcher(ex.getRequestURI().getPath());
        if ( !matcher.matches() )
            return false;
        
        int version = Integer.parseInt(matcher.group(1));
        InputStream in = getClass().getResourceAsStream("/jira/relatedIssueCounts/" + version + ".json");
        if ( in == null  ) {
            ex.sendResponseHeaders(404, -1);
        } else {
            ex.sendResponseHeaders(200, 0);
            try ( OutputStream out = ex.getResponseBody() ) {
                IOUtils.copy(in, out);
            }
        }

        return false;
    }

}
