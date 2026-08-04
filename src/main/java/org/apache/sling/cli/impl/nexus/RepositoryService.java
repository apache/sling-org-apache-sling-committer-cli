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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.ComponentContextHelper;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.nexus.StagingRepository.Status;
import org.apache.sling.cli.impl.release.Release;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(service = RepositoryService.class)
public class RepositoryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryService.class);
    private static final String REPOSITORY_PREFIX = "orgapachesling-";
    private static final String DEFAULT_NEXUS_URL_PREFIX = "https://repository.apache.org";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private Map<String, LocalRepository> repositories = new HashMap<>();
    private ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final PomParser pomParser = new PomParser();

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
                    .orElseThrow(
                            () -> new IllegalArgumentException("No repository found with id " + stagingRepositoryId));
        });
    }

    public StagingRepository findAny(int stagingRepositoryId) throws IOException {
        return this.withStagingRepositories(reader -> {
            Gson gson = new Gson();
            return gson.fromJson(reader, StagingRepositories.class).getData().stream()
                    .filter(r -> r.getRepositoryId().startsWith(REPOSITORY_PREFIX))
                    .filter(r -> r.getRepositoryId().endsWith("-" + stagingRepositoryId))
                    .findFirst()
                    .orElseThrow(
                            () -> new IllegalArgumentException("No repository found with id " + stagingRepositoryId));
        });
    }

    public void close(StagingRepository repository) throws IOException {
        executeBulkAction("close", repository.getRepositoryId(), Collections.emptyMap());
    }

    public void close(StagingRepository repository, String description) throws IOException {
        executeBulkAction("close", repository.getRepositoryId(), Collections.singletonMap("description", description));
    }

    public void promote(StagingRepository repository) throws IOException {
        // Nexus "Release": move the staged artifacts to the release repository (which syncs to Maven
        // Central) and drop the staging repository afterwards. This matches the payload the Nexus UI
        // sends. Note there is no targetRepositoryId — that field is for build-promotion profiles and
        // is rejected with HTTP 400 by the bulk/promote endpoint.
        executeBulkAction(
                "promote", repository.getRepositoryId(), Collections.singletonMap("autoDropAfterRelease", true));
    }

    public void drop(StagingRepository repository) throws IOException {
        executeBulkAction("delete", repository.getRepositoryId(), Collections.emptyMap());
    }

    private void executeBulkAction(String action, String repositoryId, Map<String, Object> extraData)
            throws IOException {
        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            HttpPost post = new HttpPost(nexusUrlPrefix + "/service/local/staging/bulk/" + action);
            post.addHeader(HttpHeaders.ACCEPT, CONTENT_TYPE_JSON);

            Map<String, Object> data = new HashMap<>();
            data.put("stagedRepositoryIds", Collections.singletonList(repositoryId));
            data.put("description", "");
            data.putAll(extraData);

            JsonObject body = new JsonObject();
            body.add("data", new Gson().toJsonTree(data));

            post.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));

            try (CloseableHttpResponse response = client.execute(post)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != 201) {
                    throw new IOException(
                            "Unexpected status " + statusCode + " for staging bulk/" + action + " on " + repositoryId);
                }
            }
        }
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
                            String relativeFolderPath =
                                    fileRelativePath.substring(0, fileRelativePath.lastIndexOf('/'));
                            Path artifactFolderPath = Files.createDirectories(rootFolder.resolve(relativeFolderPath));
                            downloadFileFromRepository(repository, client, artifactFolderPath, fileRelativePath);
                            downloadFileFromRepository(
                                    repository,
                                    client,
                                    artifactFolderPath,
                                    artifact.getRepositoryRelativeSignaturePath());
                            downloadFileFromRepository(
                                    repository,
                                    client,
                                    artifactFolderPath,
                                    artifact.getRepositoryRelativeSha1SumPath());
                            downloadFileFromRepository(
                                    repository, client, artifactFolderPath, artifact.getRepositoryRelativeMd5SumPath());
                            // the .sha512 sidecar is produced by the Apache release build for the
                            // source-release archive only, so it is absent for most artifacts; download
                            // it when present (a 404 is expected for the others and simply skipped)
                            downloadFileFromRepository(
                                    repository,
                                    client,
                                    artifactFolderPath,
                                    artifact.getRepositoryRelativeSha512SumPath());
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
            HttpGet get = newGet(
                    "/service/local/lucene/search?g=org.apache.sling&repositoryId=" + repository.getRepositoryId());
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
                        JsonArray artifactLinksArray = dataElementJson
                                .get("artifactHits")
                                .getAsJsonArray()
                                .get(0)
                                .getAsJsonObject()
                                .get("artifactLinks")
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
                    throw new IOException(
                            String.format("Got %d instead of 200 when retrieving %s.", statusCode, get.getURI()));
                }
                consumer.accept(response.getEntity().getContent());
            }
        }
    }

    public Set<Release> getReleases(StagingRepository stagingRepository) throws IOException {
        return PomParser.toReleases(readStagedPoms(stagingRepository));
    }

    /**
     * Returns the artifact ids belonging to {@code release}, resolved from the POMs staged in
     * {@code stagingRepository}. Used to key website updates on the artifact id rather than on the
     * human-readable component name, which frequently differs from what the site lists.
     */
    public Set<String> getArtifactIds(StagingRepository stagingRepository, Release release) throws IOException {
        return PomParser.artifactIdsFor(readStagedPoms(stagingRepository), release);
    }

    private List<PomParser.PomCoordinates> readStagedPoms(StagingRepository stagingRepository) throws IOException {
        List<PomParser.PomCoordinates> poms = new ArrayList<>();
        getArtifacts(stagingRepository).stream()
                .filter(artifact -> "pom".equals(artifact.getType()))
                .forEach(pom -> {
                    try {
                        processArtifactStream(pom, stream -> {
                            PomParser.PomCoordinates coordinates = pomParser.parse(stream, pom.toString());
                            if (coordinates != null) {
                                poms.add(coordinates);
                            }
                        });
                    } catch (IOException e) {
                        LOGGER.error(String.format("Unable to process artifact %s.", pom), e);
                    }
                });
        return poms;
    }

    /**
     * Resolves the artifact ids belonging to {@code release} by reading the released POMs published at
     * {@code baseUrl} (dist.apache.org). Used when the staging repository is gone — after promotion it is
     * dropped, so a resumed run has no staged POMs to consult, but the released ones are still available.
     *
     * <p>{@code pomFileNames} are the {@code <artifactId>-<version>.pom} names already known to carry the
     * release's version. Several unrelated artifacts share a version (29 different artifacts sit at
     * {@code 1.0.0}), so the version alone does not identify the release; each candidate POM is read and
     * matched on its {@code <name>}, which is exactly what the release name was derived from.
     */
    public Set<String> getArtifactIdsFromPomUrls(String baseUrl, Collection<String> pomFileNames, Release release)
            throws IOException {
        List<PomParser.PomCoordinates> poms = new ArrayList<>();
        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            for (String pomFileName : pomFileNames) {
                HttpGet get = new HttpGet(baseUrl + pomFileName);
                try (CloseableHttpResponse response = client.execute(get)) {
                    if (response.getStatusLine().getStatusCode() != 200) {
                        continue;
                    }
                    try (InputStream stream = response.getEntity().getContent()) {
                        PomParser.PomCoordinates coordinates = pomParser.parse(stream, pomFileName);
                        if (coordinates != null) {
                            poms.add(coordinates);
                        }
                    }
                }
            }
        }
        return PomParser.artifactIdsFor(poms, release);
    }

    /**
     * Determines the releases contained in a staging repository by browsing its content directly,
     * rather than relying on the Nexus Lucene search index. This works for <em>open</em> staging
     * repositories too, whereas {@link #getReleases(StagingRepository)} only sees repositories that
     * have already been closed and indexed.
     */
    public Set<Release> getReleasesFromContent(StagingRepository repository) throws IOException {
        List<PomParser.PomCoordinates> poms = new ArrayList<>();
        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            List<String> pomPaths = new ArrayList<>();
            collectPomPaths(client, repository.getRepositoryId(), "/org/apache/sling/", pomPaths);
            for (String pomPath : pomPaths) {
                HttpGet get =
                        newGet("/service/local/repositories/" + repository.getRepositoryId() + "/content" + pomPath);
                try (CloseableHttpResponse response = client.execute(get)) {
                    if (response.getStatusLine().getStatusCode() != 200) {
                        continue;
                    }
                    try (InputStream stream = response.getEntity().getContent()) {
                        PomParser.PomCoordinates coordinates = pomParser.parse(stream, pomPath);
                        if (coordinates != null) {
                            poms.add(coordinates);
                        }
                    }
                }
            }
        }
        return PomParser.toReleases(poms);
    }

    private void collectPomPaths(CloseableHttpClient client, String repositoryId, String path, List<String> pomPaths)
            throws IOException {
        HttpGet get = newGet("/service/local/repositories/" + repositoryId + "/content" + path);
        try (CloseableHttpResponse response = client.execute(get)) {
            if (response.getStatusLine().getStatusCode() != 200) {
                return;
            }
            try (InputStream content = response.getEntity().getContent();
                    InputStreamReader reader = new InputStreamReader(content)) {
                JsonArray data = new JsonParser()
                        .parse(reader)
                        .getAsJsonObject()
                        .get("data")
                        .getAsJsonArray();
                for (JsonElement element : data) {
                    JsonObject entry = element.getAsJsonObject();
                    String relativePath = entry.get("relativePath").getAsString();
                    if (entry.get("leaf").getAsBoolean()) {
                        if (entry.get("text").getAsString().endsWith(".pom")) {
                            pomPaths.add(relativePath);
                        }
                    } else {
                        collectPomPaths(client, repositoryId, relativePath, pomPaths);
                    }
                }
            }
        }
    }

    private boolean downloadFileFromRepository(
            @NotNull StagingRepository repository,
            @NotNull CloseableHttpClient client,
            @NotNull Path artifactFolderPath,
            @NotNull String relativeFilePath)
            throws IOException {
        HttpGet get = new HttpGet(repository.getRepositoryURI() + "/" + relativeFilePath);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Downloading {}.", get.getURI());
        }
        try (CloseableHttpResponse response = client.execute(get)) {
            // skip files the repository does not have so an error body is never written to disk as if it
            // were the artifact; sidecars such as .sha512 legitimately exist only for some artifacts
            if (response.getStatusLine().getStatusCode() != 200) {
                return false;
            }
            String fileName = relativeFilePath.substring(relativeFilePath.lastIndexOf('/') + 1);
            Path filePath = Files.createFile(artifactFolderPath.resolve(fileName));
            try (InputStream content = response.getEntity().getContent()) {
                IOUtils.copyLarge(content, Files.newOutputStream(filePath));
            }
            return true;
        }
    }

    private HttpGet newGet(String suffix) {
        HttpGet get = new HttpGet(nexusUrlPrefix + suffix);
        get.addHeader(HttpHeaders.ACCEPT, CONTENT_TYPE_JSON);
        return get;
    }
}
