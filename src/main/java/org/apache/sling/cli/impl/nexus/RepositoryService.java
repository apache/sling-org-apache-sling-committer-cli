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
package org.apache.sling.cli.impl.nexus;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.ComponentContextHelper;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.nexus.StagingRepository.Status;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@Component(service = RepositoryService.class)
public class RepositoryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryService.class);
    private static final String REPOSITORY_PREFIX = "orgapachesling-";
    private static final String DEFAULT_NEXUS_URL_PREFIX = "https://repository.apache.org";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private Map<String, LocalRepository> repositories = new HashMap<>();
    private ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

    @Reference
    private HttpClientFactory httpClientFactory;
    private String nexusUrlPrefix;

    @Activate
    private void activate(ComponentContext componentContext) {
        ComponentContextHelper helper = ComponentContextHelper.wrap(componentContext);
        nexusUrlPrefix = helper.getProperty("nexus.url.prefix", DEFAULT_NEXUS_URL_PREFIX);
    }

    public List<StagingRepository> list() throws IOException {
        return this.withStagingRepositories(reader -> {
            Gson gson = new Gson();
            return gson.fromJson(reader, StagingRepositories.class).getData().stream()
                    .filter(r -> r.getType() == Status.closed)
                    .filter(r -> r.getRepositoryId().startsWith(REPOSITORY_PREFIX))
                    .collect(Collectors.toList());
        });
    }

    public StagingRepository find(int stagingRepositoryId) throws IOException {
        return this.withStagingRepositories(reader -> {
            Gson gson = new Gson();
            return gson.fromJson(reader, StagingRepositories.class).getData().stream()
                    .filter(r -> r.getType() == Status.closed)
                    .filter(r -> r.getRepositoryId().startsWith(REPOSITORY_PREFIX))
                    .filter(r -> r.getRepositoryId().endsWith("-" + stagingRepositoryId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No repository found with id " + stagingRepositoryId));
        });
    }

    private <T> T withStagingRepositories(Function<InputStreamReader, T> function) throws IOException {
        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            HttpGet get = newGet("/service/local/staging/profile_repositories");
            try (CloseableHttpResponse response = client.execute(get)) {
                try (InputStream content = response.getEntity().getContent();
                     InputStreamReader reader = new InputStreamReader(content)) {
                    if (response.getStatusLine().getStatusCode() != 200) {
                        throw new IOException("Status line : " + response.getStatusLine());
                    }
                    return function.apply(reader);
                }
            }
        }
    }

    @NotNull
    public LocalRepository download(@NotNull StagingRepository repository) throws IOException {
        readWriteLock.readLock().lock();
        LocalRepository localRepository = repositories.get(repository.getRepositoryId());
        if (localRepository == null) {
            readWriteLock.readLock().unlock();
            readWriteLock.writeLock().lock();
            try {
                if (!repositories.containsKey(repository.getRepositoryId())) {
                    Path rootFolder = Files.createTempDirectory(repository.getRepositoryId() + "_");
                    Set<Artifact> artifacts = getArtifacts(repository);
                    try (CloseableHttpClient client = httpClientFactory.newClient()) {
                        for (Artifact artifact : artifacts) {
                            String fileRelativePath = artifact.getRepositoryRelativePath();
                            String relativeFolderPath = fileRelativePath.substring(0, fileRelativePath.lastIndexOf('/'));
                            Path artifactFolderPath = Files.createDirectories(rootFolder.resolve(relativeFolderPath));
                            downloadFileFromRepository(repository, client, artifactFolderPath, fileRelativePath);
                            downloadFileFromRepository(repository, client, artifactFolderPath,
                                    artifact.getRepositoryRelativeSignaturePath());
                            downloadFileFromRepository(repository, client, artifactFolderPath,
                                    artifact.getRepositoryRelativeSha1SumPath());
                            downloadFileFromRepository(repository, client, artifactFolderPath,
                                    artifact.getRepositoryRelativeMd5SumPath());
                        }
                    }
                    localRepository = new LocalRepository(repository, artifacts, rootFolder);
                    repositories.put(localRepository.getRepositoryId(), localRepository);
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

    public Set<Artifact> getArtifacts(StagingRepository repository) throws IOException {
        Set<Artifact> artifacts = new HashSet<>();
        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            HttpGet get =
                    newGet("/service/local/lucene/search?g=org.apache.sling&repositoryId=" +
                            repository.getRepositoryId());
            try (CloseableHttpResponse response = client.execute(get)) {
                try (InputStream content = response.getEntity().getContent();
                     InputStreamReader reader = new InputStreamReader(content)) {
                    JsonParser parser = new JsonParser();
                    JsonObject json = parser.parse(reader).getAsJsonObject();
                    JsonArray data = json.get("data").getAsJsonArray();

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
                            artifacts.add(new Artifact(repository, groupId, artifactId, version, classifier, type));
                        }
                    }
                }
            }
        }
        return artifacts;
    }

    public void processArtifactStream(Artifact artifact, Consumer<InputStream> consumer) throws IOException {
        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            HttpGet get = new HttpGet(artifact.getUri());
            try (CloseableHttpResponse response = client.execute(get)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != 200) {
                    throw new IOException(String.format("Got %d instead of 200 when retrieving %s.", statusCode, get.getURI()));
                }
                consumer.accept(response.getEntity().getContent());
            }
        }
    }

    private void downloadFileFromRepository(@NotNull StagingRepository repository, @NotNull CloseableHttpClient client,
                                            @NotNull Path artifactFolderPath, @NotNull String relativeFilePath) throws IOException {
        String fileName = relativeFilePath.substring(relativeFilePath.lastIndexOf('/') + 1);
        Path filePath = Files.createFile(artifactFolderPath.resolve(fileName));
        HttpGet get = new HttpGet(repository.getRepositoryURI() + "/" + relativeFilePath);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Downloading {}.", get.getURI().toString());
        }
        try (CloseableHttpResponse response = client.execute(get)) {
            try (InputStream content = response.getEntity().getContent()) {
                IOUtils.copyLarge(content, Files.newOutputStream(filePath));
            }
        }
    }

    private HttpGet newGet(String suffix) {
        HttpGet get = new HttpGet(nexusUrlPrefix + suffix);
        get.addHeader("Accept", CONTENT_TYPE_JSON);
        return get;
    }

}
