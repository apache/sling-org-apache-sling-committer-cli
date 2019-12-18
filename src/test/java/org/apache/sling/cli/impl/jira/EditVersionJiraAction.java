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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.sling.cli.impl.DateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;

public class EditVersionJiraAction implements JiraAction {

    private static final Logger LOGGER = LoggerFactory.getLogger(EditVersionJiraAction.class);
    private static final Pattern VERSION_ID = Pattern.compile("/jira/rest/api/2/version/\\d+$");
    private static final String ALREADY_RELEASED = "/jira/rest/api/2/version/1";

    @Override
    public boolean tryHandle(HttpExchange ex) throws IOException {
        if (!VERSION_ID.matcher(ex.getRequestURI().getPath()).matches()) {
            return false;
        }
        if (ALREADY_RELEASED.equals(ex.getRequestURI().getPath())) {
            // make sure we cannot update an already released version
            ex.sendResponseHeaders(500, -1);
        }
        String versionIdString = ex.getRequestURI().getPath().substring(25);
        try {
            int versionId = Integer.parseInt(versionIdString);
            try (InputStream in = getClass().getResourceAsStream("/jira/versions.json")) {
                if (in == null) {
                    ex.sendResponseHeaders(404, -1);
                    return true;
                }
                try (InputStreamReader reader = new InputStreamReader(in)) {
                    Gson gson = new Gson();
                    Type collectionType = TypeToken.getParameterized(List.class, Version.class).getType();
                    List<Version> versions = gson.fromJson(reader, collectionType);
                    Optional<Version> versionHolder = versions.stream().filter(v -> versionId == v.getId()).findFirst();
                    if (versionHolder.isEmpty()) {
                        ex.sendResponseHeaders(404, -1);
                    } else {
                        if ("PUT".equals(ex.getRequestMethod())) {
                            // version change
                            Version version = versionHolder.get();
                            try (InputStreamReader requestReader = new InputStreamReader(ex.getRequestBody())) {
                                VersionToUpdate versionToUpdate = gson.fromJson(requestReader, VersionToUpdate.class);
                                DateProvider dateProvider = new DateProvider();
                                if (versionToUpdate.released != version.isReleased() && versionToUpdate.released &&
                                        dateProvider.getCurrentDateForJiraRelease().equals(versionToUpdate.releaseDate) &&
                                        version.getReleaseDate() == null) {
                                    ex.sendResponseHeaders(200, -1);
                                }
                            }
                        }
                        ex.sendResponseHeaders(406, -1);
                    }
                }
            }

        } catch (NumberFormatException e) {
            LOGGER.error("Unable to parse version id from " + ex.getRequestURI().getPath(), e);
            ex.sendResponseHeaders(400, -1);
        }
        return true;
    }

    @SuppressWarnings("unused")
    static class VersionToUpdate {
        private String description;
        private String name;
        private boolean archived;
        private boolean released;
        private String releaseDate;
        private String userReleaseDate;
        private String projectId;
    }
}
