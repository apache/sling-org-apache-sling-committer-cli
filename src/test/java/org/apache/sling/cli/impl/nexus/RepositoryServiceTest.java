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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.junit.SystemPropertiesRule;
import org.apache.sling.cli.impl.release.Release;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RepositoryServiceTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryServiceTest.class);
    private static final Map<String, String> SYSTEM_PROPS = new HashMap<>();

    static {
        SYSTEM_PROPS.put("asf.username", "asf-user");
        SYSTEM_PROPS.put("asf.password", "asf-password");
        SYSTEM_PROPS.put("jira.username", "jira-user");
        SYSTEM_PROPS.put("jira.password", "jira-password");
    }

    private RepositoryService repositoryService;

    @Rule
    public final OsgiContext context = new OsgiContext();

    @Rule
    public final SystemPropertiesRule sysProps = new SystemPropertiesRule(SYSTEM_PROPS);

    @Rule
    public MockNexus nexus = new MockNexus();

    @Before
    public void prepareDependencies() {
        context.registerInjectActivateService(new CredentialsService());
        context.registerInjectActivateService(
                new HttpClientFactory(), "nexus.host", "localhost", "nexus.port", nexus.getBoundPort());
        repositoryService = context.registerInjectActivateService(
                new RepositoryService(), "nexus.url.prefix", "http://localhost:" + nexus.getBoundPort());
    }

    @Test
    public void testLuceneSearch() throws IOException {
        Set<Artifact> artifacts = repositoryService.getArtifacts(getStagingRepository());
        assertEquals(5, artifacts.size());
    }

    @Test
    public void testRepositoryFind() throws IOException {
        StagingRepository stagingRepository = repositoryService.find(0);
        assertNotNull(stagingRepository);
    }

    @Test
    public void testRepositoryList() throws IOException {
        List<StagingRepository> stagingRepositories = repositoryService.list();
        // Includes both closed repositories and the open (not yet closed) one, so that newly
        // staged repositories show up before they have been closed for voting.
        assertEquals(3, stagingRepositories.size());
        Set<String> repositoriesIds = new HashSet<>(Set.of("orgapachesling-0", "orgapachesling-1", "orgapachesling-2"));
        for (StagingRepository repository : stagingRepositories) {
            assertEquals(
                    "http://localhost:" + nexus.getBoundPort() + "/content/repositories/"
                            + repository.getRepositoryId(),
                    repository.repositoryURI);
            repositoriesIds.remove(repository.getRepositoryId());
        }
        assertTrue(repositoriesIds.isEmpty());
    }

    @Test
    public void testArtifactStream() throws IOException {
        Set<Artifact> artifacts = repositoryService.getArtifacts(getStagingRepository());
        AtomicReference<Boolean> processed = new AtomicReference<>();
        processed.set(false);
        for (Artifact artifact : artifacts) {
            if ("pom".equals(artifact.getType())) {
                repositoryService.processArtifactStream(artifact, inputStream -> {
                    try (InputStream stream = inputStream) {
                        assertEquals(
                                IOUtils.resourceToString(
                                        "/nexus/orgapachesling-0/org/apache/sling/adapter-annotations/1.0"
                                                + ".0/adapter-annotations-1.0.0.pom",
                                        StandardCharsets.UTF_8),
                                IOUtils.toString(stream, StandardCharsets.UTF_8));
                        processed.set(true);
                    } catch (IOException e) {
                        fail("Failed to read POM file.");
                    }
                });
            }
        }
        assertTrue(processed.get());
    }

    @Test
    public void testDownloadRepository() throws IOException {
        StagingRepository stagingRepository = getStagingRepository();
        LocalRepository localRepository = repositoryService.download(stagingRepository);
        assertNotNull(localRepository);
        for (Artifact artifact : localRepository.getArtifacts()) {
            assertTrue(Files.exists(localRepository.getRootFolder().resolve(artifact.getRepositoryRelativePath())));
        }
        List<Path> artifactFiles;
        try (Stream<Path> paths = Files.walk(localRepository.getRootFolder())) {
            artifactFiles = paths.filter(Files::isRegularFile).toList();
        }
        LOGGER.debug("Cleaning {}.", localRepository.getRootFolder());
        for (Path artifactFile : artifactFiles) {
            LOGGER.debug("Deleting file {}.", artifactFile.toString());
            Files.delete(artifactFile);
        }
        List<Path> emptyDirectories;
        try (Stream<Path> paths = Files.walk(localRepository.getRootFolder())) {
            emptyDirectories = paths.filter(Files::isDirectory).collect(Collectors.toList());
        }
        Collections.reverse(emptyDirectories);
        for (Path directory : emptyDirectories) {
            LOGGER.debug("Deleting empty folder {}.", directory.toString());
            Files.delete(directory);
        }
    }

    @Test
    public void testDownloadRepositoryFetchesSha512Sidecar() throws IOException {
        // the Apache release build emits a .sha512 for the source-release archive only; it must be
        // downloaded so update-dist can publish it, while artifacts without one are not fabricated
        LocalRepository localRepository = repositoryService.download(getStagingRepository());
        Path base = localRepository.getRootFolder().resolve("org/apache/sling/adapter-annotations/1.0.0");
        assertTrue(
                "source-release .sha512 should be downloaded",
                Files.exists(base.resolve("adapter-annotations-1.0.0-source-release.zip.sha512")));
        assertTrue(
                "no bogus .sha512 should be created for artifacts that lack one",
                Files.notExists(base.resolve("adapter-annotations-1.0.0.jar.sha512")));
    }

    @Test
    public void testReleaseLookup() throws IOException {
        StagingRepository stagingRepository = getStagingRepository();
        Set<Release> releases = repositoryService.getReleases(stagingRepository);
        assertEquals(1, releases.size());
        Release release = releases.iterator().next();
        assertEquals("Sling Adapter Annotations 1.0.0", release.getFullName());
    }

    @Test
    public void testGetReleasesFromContent() throws IOException {
        // browses the repository content tree directly (no Lucene index), recursing into directories
        // and parsing every .pom leaf it finds
        StagingRepository repository = new StagingRepository();
        repository.setRepositoryId("orgapachesling-3");
        Set<Release> releases = repositoryService.getReleasesFromContent(repository);
        assertEquals(1, releases.size());
        assertEquals(
                "Sling Adapter Annotations 1.0.0", releases.iterator().next().getFullName());
    }

    @Test
    public void testFindAnyReturnsOpenRepository() throws IOException {
        // findAny does not require the repository to be closed, so the open orgapachesling-2 resolves
        StagingRepository repository = repositoryService.findAny(2);
        assertNotNull(repository);
        assertEquals("orgapachesling-2", repository.getRepositoryId());
    }

    @Test
    public void testFindAnyUnknownThrows() {
        try {
            repositoryService.findAny(999);
            fail("Expected an IllegalArgumentException for an unknown repository id.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("999"));
        } catch (IOException e) {
            fail("Unexpected IOException.");
        }
    }

    @Test
    public void testPromote() throws IOException {
        repositoryService.promote(getStagingRepository());
        assertEquals("promote", nexus.getLastBulkAction());
    }

    @Test
    public void testCloseWithDescription() throws IOException {
        repositoryService.close(getStagingRepository(), "voting");
        assertEquals("close", nexus.getLastBulkAction());
    }

    @Test
    public void testDrop() throws IOException {
        repositoryService.drop(getStagingRepository());
        assertEquals("delete", nexus.getLastBulkAction());
    }

    @Test
    public void testGetArtifactIdsFromStagedPoms() throws IOException {
        // the website update keys downloads entries on the artifact id, resolved from the staged POMs
        Set<String> artifactIds = repositoryService.getArtifactIds(
                getStagingRepository(),
                Release.fromString("Sling Adapter Annotations 1.0.0").get(0));

        assertEquals(Set.of("adapter-annotations"), artifactIds);
    }

    @Test
    public void testGetArtifactIdsForAnUnrelatedReleaseIsEmpty() throws IOException {
        Set<String> artifactIds = repositoryService.getArtifactIds(
                getStagingRepository(),
                Release.fromString("Sling Something Else 9.9.9").get(0));

        assertTrue(artifactIds.isEmpty());
    }

    @Test
    public void testGetArtifactIdsFromPomUrls() throws IOException {
        // the resume-by-name path reads the released POMs instead of the staged ones, matching each on
        // its <name>; a name that is not published is skipped rather than failing the lookup
        Set<String> artifactIds = repositoryService.getArtifactIdsFromPomUrls(
                pomBaseUrl(),
                List.of("adapter-annotations-1.0.0.pom", "does-not-exist-1.0.0.pom"),
                Release.fromString("Sling Adapter Annotations 1.0.0").get(0));

        assertEquals(Set.of("adapter-annotations"), artifactIds);
    }

    @Test
    public void testGetArtifactIdsFromPomUrlsWithoutMatchesIsEmpty() throws IOException {
        Set<String> artifactIds = repositoryService.getArtifactIdsFromPomUrls(
                pomBaseUrl(),
                List.of("adapter-annotations-1.0.0.pom"),
                Release.fromString("Sling Adapter Annotations 2.0.0").get(0));

        assertTrue(artifactIds.isEmpty());
    }

    /** Serves the fixture POMs as if they were published in a flat directory, like {@code dist/release}. */
    private String pomBaseUrl() {
        return "http://localhost:" + nexus.getBoundPort()
                + "/service/local/repositories/orgapachesling-3/content/org/apache/sling/adapter-annotations/1.0.0/";
    }

    private StagingRepository getStagingRepository() {
        StagingRepository stagingRepository = new StagingRepository();
        stagingRepository.setRepositoryId("orgapachesling-0");
        stagingRepository.setRepositoryURI(
                "http://localhost:" + nexus.getBoundPort() + "/content/repositories/orgapachesling-0");
        return stagingRepository;
    }
}
