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
package org.apache.sling.cli.impl.nexus;

import java.util.Objects;

/**
 * An {@code Artifact} describes a Maven artifact stored in a Maven repository (remote or local).
 */
public class Artifact {

    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String classifier;
    private final String type;
    private final String repositoryRelativePath;
    private final String repositoryRelativeSignaturePath;
    private final String repositoryRelativeSha1SumPath;
    private final String repositoryRelativeMd5SumPath;

    public Artifact(String groupId, String artifactId, String version, String classifier, String type) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.classifier = classifier;
        this.type = type;
        String base = groupId.replaceAll("\\.", "/") + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version;
        StringBuilder stringBuilder = new StringBuilder(base);
        if (this.classifier != null) {
            stringBuilder.append("-").append(this.classifier);
        }
        stringBuilder.append(".").append(this.type);
        repositoryRelativePath = stringBuilder.toString();
        repositoryRelativeSignaturePath = repositoryRelativePath + ".asc";
        repositoryRelativeSha1SumPath = repositoryRelativePath + ".sha1";
        repositoryRelativeMd5SumPath = repositoryRelativePath + ".md5";
    }

    public String getRepositoryRelativePath() {
        return repositoryRelativePath;
    }

    public String getRepositoryRelativeSignaturePath() {
        return repositoryRelativeSignaturePath;
    }

    public String getRepositoryRelativeSha1SumPath() {
        return repositoryRelativeSha1SumPath;
    }

    public String getRepositoryRelativeMd5SumPath() {
        return repositoryRelativeMd5SumPath;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public String getClassifier() {
        return classifier;
    }

    public String getType() {
        return type;
    }

    @Override
    public int hashCode() {
        return repositoryRelativePath.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof Artifact) {
            Artifact other = (Artifact) obj;
            return Objects.equals(repositoryRelativePath, other.repositoryRelativePath);
        }
        return false;
    }
}
