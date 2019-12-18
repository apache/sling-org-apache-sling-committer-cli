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

public class Issue {

    private int id;
    private String key;
    private Fields fields;
    
    public int getId() {
        return id;
    }
    
    public String getKey() {
        return key;
    }
    
    public String getSummary() {
        return fields.summary;
    }

    public String getStatus() {
        if (fields.status != null) {
            return fields.status.name;
        }
        return null;
    }

    public String getResolution() {
        if (fields.resolution != null) {
            return fields.resolution.name;
        }
        return null;
    }
    
    static class Fields {
        private String summary;
        private Status status;
        private Resolution resolution;

        static class Status {
            private String name;
        }

        static class Resolution {
            private String name;
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (obj instanceof Issue) {
            Issue other = (Issue) obj;
            return id == other.id;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" +  key + " : " + getSummary() + " ]"; 
    }
}
