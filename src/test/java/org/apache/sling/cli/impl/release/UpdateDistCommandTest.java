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
package org.apache.sling.cli.impl.release;

import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.junit.LogCapture;
import org.apache.sling.cli.impl.nexus.Artifact;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.MockedStatic;
import picocli.CommandLine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class UpdateDistCommandTest {

    private static final String ARTIFACT = "org.apache.sling.feature.launcher";

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(UpdateDistCommand.class);

    // ---- auto-deduce of the previous release files (listPreviousReleaseFiles) ----

    @Test
    public void testAutoDeducePreviousFilesExcludesNewVersionAndSiblings() throws Exception {
        List<String> releaseDir = List.of(
                ARTIFACT + "-1.3.4.pom",
                ARTIFACT + "-1.3.4.pom.asc",
                ARTIFACT + "-1.3.4-source-release.zip",
                ARTIFACT + "-1.3.4-source-release.zip.asc",
                ARTIFACT + "-1.3.6.pom", // the new version - must be kept (not removed)
                ARTIFACT + "-1.3.6.pom.asc",
                ARTIFACT + "-extra-1.0.0.pom" // a sibling artifact - must be ignored
                );
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS)) {
            dist.when(() -> UpdateDistCommand.listSvnFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), anyString()))
                    .thenReturn(releaseDir);

            List<String> old = UpdateDistCommand.listPreviousReleaseFiles(ARTIFACT, "1.3.6", null);

            assertEquals(4, old.size());
            assertTrue(old.contains(ARTIFACT + "-1.3.4.pom"));
            assertTrue(old.contains(ARTIFACT + "-1.3.4-source-release.zip"));
            // the version being published is never removed
            assertFalse(old.contains(ARTIFACT + "-1.3.6.pom"));
            assertFalse(old.contains(ARTIFACT + "-1.3.6.pom.asc"));
            // sibling artifact with a non-numeric component is ignored
            assertFalse(old.contains(ARTIFACT + "-extra-1.0.0.pom"));
        }
    }

    @Test
    public void testAutoDeduceDoesNotConfuseVersionPrefixes() throws Exception {
        // publishing 1.0.14 must not treat 1.0.140 as the same version
        List<String> releaseDir = List.of(ARTIFACT + "-1.0.140.pom", ARTIFACT + "-1.0.14.pom");
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS)) {
            dist.when(() -> UpdateDistCommand.listSvnFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), anyString()))
                    .thenReturn(releaseDir);

            List<String> old = UpdateDistCommand.listPreviousReleaseFiles(ARTIFACT, "1.0.14", null);

            // 1.0.140 is a different version and is removed; 1.0.14 (being published) is kept
            assertEquals(List.of(ARTIFACT + "-1.0.140.pom"), old);
        }
    }

    @Test
    public void testExplicitPreviousVersionWins() throws Exception {
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS)) {
            dist.when(() -> UpdateDistCommand.listSvnFiles(
                            eq(UpdateDistCommand.DIST_RELEASE_URL), eq(ARTIFACT + "-1.3.4")))
                    .thenReturn(List.of(ARTIFACT + "-1.3.4.pom"));

            List<String> old = UpdateDistCommand.listPreviousReleaseFiles(ARTIFACT, "1.3.6", "1.3.4");

            assertEquals(List.of(ARTIFACT + "-1.3.4.pom"), old);
            // when an explicit version is given, the directory is not enumerated with the bare prefix
            dist.verify(
                    () -> UpdateDistCommand.listSvnFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), eq(ARTIFACT + "-")),
                    never());
        }
    }

    // ---- full command flow ----

    @Test
    public void testDryRunDescribesMoveAndRemoveWithoutCommitting() throws Exception {
        prepareRepositoryService();
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS)) {
            dist.when(() -> UpdateDistCommand.listSvnFiles(eq(UpdateDistCommand.DIST_DEV_URL), anyString()))
                    .thenReturn(List.of(ARTIFACT + "-1.3.6-source-release.zip"));
            dist.when(() -> UpdateDistCommand.listSvnFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), anyString()))
                    .thenReturn(List.of(ARTIFACT + "-1.3.4.pom"));

            Command command = createCommand(ExecutionMode.DRY_RUN, null);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            assertTrue(logCapture.containsMessage("Would move 1 file(s) from dist/dev to dist/release:"));
            assertTrue(logCapture.containsMessage("Would remove 1 old file(s) from dist/release:"));
            dist.verify(() -> UpdateDistCommand.runSvnMucc(any(), any(), any(), any(), any()), never());
        }
    }

    @Test
    public void testAutoCommitsViaSvnMucc() throws Exception {
        prepareRepositoryService();
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS)) {
            dist.when(() -> UpdateDistCommand.listSvnFiles(eq(UpdateDistCommand.DIST_DEV_URL), anyString()))
                    .thenReturn(List.of(ARTIFACT + "-1.3.6-source-release.zip"));
            dist.when(() -> UpdateDistCommand.listSvnFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), anyString()))
                    .thenReturn(List.of(ARTIFACT + "-1.3.4.pom"));
            // do not actually shell out to svnmucc
            dist.when(() -> UpdateDistCommand.runSvnMucc(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> null);

            Command command = createCommand(ExecutionMode.AUTO, null);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            dist.verify(() -> UpdateDistCommand.runSvnMucc(eq(ARTIFACT), eq("1.3.6"), any(), any(), any()));
        }
    }

    @Test
    public void testNoNewFilesReturnsUsage() throws Exception {
        prepareRepositoryService();
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS)) {
            dist.when(() -> UpdateDistCommand.listSvnFiles(eq(UpdateDistCommand.DIST_DEV_URL), anyString()))
                    .thenReturn(List.of());
            // the release directory listing is computed before the empty-new-files check; stub it so
            // the real `svn` is never invoked
            dist.when(() -> UpdateDistCommand.listSvnFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), anyString()))
                    .thenReturn(List.of());

            Command command = createCommand(ExecutionMode.AUTO, null);
            assertEquals(CommandLine.ExitCode.USAGE, (int) command.call());
            dist.verify(() -> UpdateDistCommand.runSvnMucc(any(), any(), any(), any(), any()), never());
        }
    }

    private void prepareRepositoryService() throws Exception {
        StagingRepository repository = mock(StagingRepository.class);
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.find(123)).thenReturn(repository);
        Artifact pom = new Artifact(repository, "org.apache.sling", ARTIFACT, "1.3.6", null, "pom");
        when(repositoryService.getArtifacts(repository)).thenReturn(Set.of(pom));

        CredentialsService credentialsService = mock(CredentialsService.class);
        when(credentialsService.getAsfCredentials()).thenReturn(new Credentials("johndoe", "secret"));

        osgiContext.registerService(RepositoryService.class, repositoryService);
        osgiContext.registerService(CredentialsService.class, credentialsService);
    }

    private Command createCommand(ExecutionMode executionMode, String previousVersion) throws IllegalAccessException {
        UpdateDistCommand updateDistCommand = spy(new UpdateDistCommand());
        FieldUtils.writeField(updateDistCommand, "repositoryId", 123, true);
        FieldUtils.writeField(updateDistCommand, "previousVersion", previousVersion, true);
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        FieldUtils.writeField(reusableCLIOptions, "executionMode", executionMode, true);
        FieldUtils.writeField(updateDistCommand, "reusableCLIOptions", reusableCLIOptions, true);
        osgiContext.registerInjectActivateService(updateDistCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the UpdateDistCommand from the mocked OSGi environment.",
                result instanceof UpdateDistCommand);
        return result;
    }
}
