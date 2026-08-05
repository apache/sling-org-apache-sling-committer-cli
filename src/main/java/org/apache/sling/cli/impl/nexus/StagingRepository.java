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
package org.apache.sling.cli.impl.nexus;

import java.time.Instant;

/**
 * DTO for GSON usage
 *
 */
public class StagingRepository {

    enum Status {
        open,
        closed;
    }

    protected String description;
    protected String repositoryId;
    protected String repositoryURI;
    protected Status type;
    protected String userId;
    protected long createdTimestamp;

    /**
     * Returns the moment this staging repository was created in Nexus, i.e. when the release artifacts
     * were staged. Anything resolved in JIRA after this point cannot be part of the release.
     *
     * @return the creation instant, or {@code null} if Nexus did not report one
     */
    public Instant getCreated() {
        return createdTimestamp > 0 ? Instant.ofEpochMilli(createdTimestamp) : null;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the id of the committer who staged this repository, as reported by Nexus.
     *
     * @return the staging user id
     */
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    public String getRepositoryURI() {
        return repositoryURI;
    }

    public void setRepositoryURI(String repositoryURI) {
        this.repositoryURI = repositoryURI;
    }

    public Status getType() {
        return type;
    }

    public void setType(Status type) {
        this.type = type;
    }
}
