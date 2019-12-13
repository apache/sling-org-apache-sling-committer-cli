/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ~ Licensed to the Apache Software Foundation (ASF) under one
 ~ or more contributor license agreements.  See the NOTICE file
 ~ distributed with this work for additional information
 ~ regarding copyright ownership.  The ASF licenses this file
 ~ to you under the Apache License, Version 2.0 (the
 ~ "License"); you may not use this file except in compliance
 ~ with the License.  You may obtain a copy of the License at
 ~
 ~   http://www.apache.org/licenses/LICENSE-2.0
 ~
 ~ Unless required by applicable law or agreed to in writing,
 ~ software distributed under the License is distributed on an
 ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 ~ KIND, either express or implied.  See the License for the
 ~ specific language governing permissions and limitations
 ~ under the License.
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/
package org.apache.sling.cli.impl.jira;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;

public class TransitionsJiraAction implements JiraAction {

    private static final Pattern TRANSITIONS = Pattern.compile("/jira/rest/api/2/issue/\\d+/transitions");
    private static final Pattern RETURN_NO_TRANSITIONS = Pattern.compile("/jira/rest/api/2/issue/(1|2|3|4)/transitions");

    @Override
    public boolean tryHandle(HttpExchange ex) throws IOException {
        if (!TRANSITIONS.matcher(ex.getRequestURI().getPath()).matches()) {
            return false;
        }
        if (ex.getRequestMethod().equals("GET")) {
            if (RETURN_NO_TRANSITIONS.matcher(ex.getRequestURI().getPath()).matches()) {
                serveFileFromClasspath(ex, "/jira/transitions/no-transitions.json");
            } else {
                serveFileFromClasspath(ex, "/jira/transitions/transitions.json");
            }
        } else if (ex.getRequestMethod().equals("POST")) {
            Gson gson = new Gson();
            try ( InputStreamReader reader = new InputStreamReader(ex.getRequestBody())) {
                TransitionToExecute transitionToExecute = gson.fromJson(reader, TransitionToExecute.class);
                if (701 == transitionToExecute.transition.getId()) {
                    ex.sendResponseHeaders(204, -1);
                } else {
                    ex.sendResponseHeaders(400, -1);
                }
            } catch (JsonParseException e) {
                ex.sendResponseHeaders(400, -1);
            }
        }
        return true;
    }

    private static class TransitionToExecute {
       private Transition transition;
    }


}
