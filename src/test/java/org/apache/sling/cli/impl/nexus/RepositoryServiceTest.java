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

import org.apache.commons.io.IOUtils;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.jira.SystemPropertiesRule;
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
        context.registerInjectActivateService(new HttpClientFactory(), "nexus.host", "localhost", "nexus.port", nexus.getBoundPort());
        repositoryService = context.registerInjectActivateService(new RepositoryService(), "nexus.url.prefix",
                "http://localhost:" + nexus.getBoundPort());
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
        assertEquals(2, stagingRepositories.size());
        Set<String> repositoriesIds = new HashSet<>(Set.of("orgapachesling-0", "orgapachesling-1"));
        for (StagingRepository repository : stagingRepositories) {
            assertEquals("http://localhost:" + nexus.getBoundPort() + "/content/repositories/" + repository.getRepositoryId(),
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
                        assertEquals(IOUtils.resourceToString("/nexus/orgapachesling-0/org/apache/sling/adapter-annotations/1.0" +
                                        ".0/adapter-annotations-1.0.0.pom", StandardCharsets.UTF_8),
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
        List<Path> artifactFiles =
                Files.walk(localRepository.getRootFolder()).filter(path -> path.toFile().isFile()).collect(Collectors.toList());
        LOGGER.debug("Cleaning {}.", localRepository.getRootFolder());
        for (Path artifactFile : artifactFiles) {
            LOGGER.debug("Deleting file {}.", artifactFile.toString());
            Files.delete(artifactFile);
        }
        List<Path> emptyDirectories =
                Files.walk(localRepository.getRootFolder()).filter(path -> path.toFile().isDirectory()).collect(Collectors.toList());
        Collections.reverse(emptyDirectories);
        for (Path directory : emptyDirectories) {
            LOGGER.debug("Deleting empty folder {}.", directory.toString());
            Files.delete(directory);
        }
    }


    private StagingRepository getStagingRepository() {
        StagingRepository stagingRepository = new StagingRepository();
        stagingRepository.setRepositoryId("orgapachesling-0");
        stagingRepository.setRepositoryURI("http://localhost:" + nexus.getBoundPort() + "/content/repositories/orgapachesling-0");
        return stagingRepository;
    }
}
