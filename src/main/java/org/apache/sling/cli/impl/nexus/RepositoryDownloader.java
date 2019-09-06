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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@Component(
        service = RepositoryDownloader.class
)
public class RepositoryDownloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryDownloader.class);

    private Map<String, LocalRepository> repositories = new HashMap<>();
    private ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

    @Reference
    private HttpClientFactory httpClientFactory;

    @NotNull
    public LocalRepository download(@NotNull StagingRepository repository) throws IOException {
        readWriteLock.readLock().lock();
        LocalRepository localRepository = repositories.get(repository.getRepositoryId());
        if (localRepository == null) {
            readWriteLock.readLock().unlock();
            readWriteLock.writeLock().lock();
            try {
                if (!repositories.containsKey(repository.getRepositoryId())) {
                    try (CloseableHttpClient client = httpClientFactory.newClient()) {
                        HttpGet get =
                                new HttpGet("https://repository.apache.org/service/local/lucene/search?g=org.apache.sling&repositoryId=" +
                                        repository.getRepositoryId());
                        get.addHeader("Accept", "application/json");
                        try (CloseableHttpResponse response = client.execute(get)) {
                            try (InputStream content = response.getEntity().getContent();
                                 InputStreamReader reader = new InputStreamReader(content)) {
                                JsonParser parser = new JsonParser();
                                JsonObject json = parser.parse(reader).getAsJsonObject();
                                JsonArray data = json.get("data").getAsJsonArray();
                                Set<Artifact> artifacts = new HashSet<>();
                                for (JsonElement dataElement : data) {
                                    JsonObject dataElementJson = dataElement.getAsJsonObject();
                                    String groupId = dataElementJson.get("groupId").getAsString();
                                    String artifactId = dataElementJson.get("artifactId").getAsString();
                                    String version = dataElementJson.get("version").getAsString();
                                    JsonArray artifactLinksArray =
                                            dataElementJson.get("artifactHits").getAsJsonArray().get(0).getAsJsonObject().get("artifactLinks")
                                                    .getAsJsonArray();
                                    for (JsonElement artifactLinkElement : artifactLinksArray) {
                                        JsonObject artifactLinkJson = artifactLinkElement.getAsJsonObject();
                                        String type = artifactLinkJson.get("extension").getAsString();
                                        String classifier = null;
                                        if (artifactLinkJson.has("classifier")) {
                                            classifier = artifactLinkJson.get("classifier").getAsString();
                                        }
                                        artifacts.add(new Artifact(groupId,artifactId, version, classifier, type));
                                    }
                                }
                                Path rootFolder = Files.createTempDirectory(repository.getRepositoryId() + "_");
                                for (Artifact artifact : artifacts) {
                                    String fileRelativePath = artifact.getRepositoryRelativePath();
                                    String relativeFolderPath = fileRelativePath.substring(0, fileRelativePath.lastIndexOf('/'));
                                    Path artifactFolderPath = Files.createDirectories(rootFolder.resolve(relativeFolderPath));
                                    downloadArtifactFile(repository, client, artifactFolderPath, fileRelativePath);
                                    downloadArtifactFile(repository, client, artifactFolderPath,
                                            artifact.getRepositoryRelativeSignaturePath());
                                    downloadArtifactFile(repository, client, artifactFolderPath,
                                            artifact.getRepositoryRelativeSha1SumPath());
                                }
                                localRepository = new LocalRepository(repository, artifacts, rootFolder);
                                repositories.put(localRepository.getRepositoryId(), localRepository);
                            }
                        }
                    }
                }
                readWriteLock.readLock().lock();
            } finally {
                readWriteLock.writeLock().unlock();
            }
        }
        try {
            if (localRepository == null) {
                throw new IOException("Failed to download repository artifacts.");
            }
            return localRepository;
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    private void downloadArtifactFile(@NotNull StagingRepository repository, CloseableHttpClient client, Path artifactFolderPath,
                                      String relativeFilePath) throws IOException {
        String fileName = relativeFilePath.substring(relativeFilePath.lastIndexOf('/') + 1);
        Path filePath = Files.createFile(artifactFolderPath.resolve(fileName));
        HttpGet get = new HttpGet(repository.getRepositoryURI() + "/" + relativeFilePath);
        LOGGER.info("Downloading " + get.getURI().toString());
        try (CloseableHttpResponse response = client.execute(get)) {
            try (InputStream content = response.getEntity().getContent()) {
                IOUtils.copyLarge(content, Files.newOutputStream(filePath));
            }
        }
    }

}
