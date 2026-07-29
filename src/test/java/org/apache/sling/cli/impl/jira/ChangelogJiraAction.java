/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.cli.impl.jira;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;

/**
 * Serves the change history for an issue GET such as {@code /jira/rest/api/2/issue/SLING-0006?expand=changelog}
 * from a per-key classpath fixture under {@code /jira/changelog/}.
 */
public class ChangelogJiraAction implements JiraAction {

    private static final Pattern ISSUE = Pattern.compile("/jira/rest/api/2/issue/([A-Za-z]+-\\d+)");

    @Override
    public boolean tryHandle(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            return false;
        }
        Matcher matcher = ISSUE.matcher(ex.getRequestURI().getPath());
        if (!matcher.matches()) {
            return false;
        }
        String key = matcher.group(1);
        try (var in = getClass().getResourceAsStream("/jira/changelog/" + key + ".json")) {
            if (in == null) {
                return false; // no fixture -> let the fallback handler answer
            }
        }
        serveFileFromClasspath(ex, "/jira/changelog/" + key + ".json");
        return true;
    }
}
