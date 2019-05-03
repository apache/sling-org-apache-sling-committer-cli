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
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;

public class CreateVersionJiraAction implements JiraAction {

    @Override
    public boolean tryHandle(HttpExchange ex) throws IOException {
        if ( !ex.getRequestMethod().equals("POST") || 
                !ex.getRequestURI().getPath().equals("/jira/rest/api/2/version") ) {
            return false;
        }

        Gson gson = new Gson();
        try ( InputStreamReader reader = new InputStreamReader(ex.getRequestBody())) {
            VersionToCreate version = gson.fromJson(reader, VersionToCreate.class);
            if ( version.getName() == null || version.getName().isEmpty() ) {
                error(ex, gson, 
                        er -> er.getErrors().put("name", "You must specify a valid version name"));
                return true;
            }
            
            if ( !"SLING".equals(version.getProject()) ) {
                error(ex, gson, 
                        er -> er.getErrorMessages().add("Project must be specified to create a version."));
                return true;
            }
            
            // note not all fields are sent, projectId and self are missing
            CreatedVersion createdVersion = new CreatedVersion();
            createdVersion.archived = false;
            createdVersion.id = ThreadLocalRandom.current().nextInt();
            createdVersion.released = false;
            createdVersion.name = version.getName();
            
            try ( OutputStreamWriter out = new OutputStreamWriter(ex.getResponseBody()) ) {
                ex.sendResponseHeaders(201, 0);
                gson.toJson(createdVersion, out);
            }
        }
        
        return true;
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
