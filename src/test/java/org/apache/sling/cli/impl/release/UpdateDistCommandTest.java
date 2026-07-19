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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.junit.LogCapture;
import org.apache.sling.cli.impl.nexus.Artifact;
import org.apache.sling.cli.impl.nexus.LocalRepository;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.io.fs.FSRepositoryFactory;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.io.diff.SVNDeltaGenerator;
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

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

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
            dist.when(() -> UpdateDistCommand.listDistFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), anyString()))
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
    public void testAutoDeduceDoesNotConfuseVersionPrefixesAndKeepsNewerVersions() throws Exception {
        // publishing 1.0.14 must not treat 1.0.140 as the same version, and must not remove it either:
        // 1.0.140 > 1.0.14, so it is a newer version and is left untouched (nothing older is present)
        List<String> releaseDir = List.of(ARTIFACT + "-1.0.140.pom", ARTIFACT + "-1.0.14.pom");
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS)) {
            dist.when(() -> UpdateDistCommand.listDistFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), anyString()))
                    .thenReturn(releaseDir);

            List<String> old = UpdateDistCommand.listPreviousReleaseFiles(ARTIFACT, "1.0.14", null);

            assertTrue("a newer version must never be removed", old.isEmpty());
        }
    }

    @Test
    public void testAutoDeduceRemovesOnlyClosestOlderVersionAcrossStreams() throws Exception {
        // parallel maintenance streams: publishing 2.0.4 must remove 2.0.2 (the closest older version)
        // but keep 1.2.4 (a different, still-maintained stream)
        List<String> releaseDir = List.of(
                ARTIFACT + "-1.2.4.pom",
                ARTIFACT + "-1.2.4-source-release.zip",
                ARTIFACT + "-2.0.2.pom",
                ARTIFACT + "-2.0.2-source-release.zip");
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS)) {
            dist.when(() -> UpdateDistCommand.listDistFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), anyString()))
                    .thenReturn(releaseDir);

            List<String> old = UpdateDistCommand.listPreviousReleaseFiles(ARTIFACT, "2.0.4", null);

            assertEquals(2, old.size());
            assertTrue(old.contains(ARTIFACT + "-2.0.2.pom"));
            assertTrue(old.contains(ARTIFACT + "-2.0.2-source-release.zip"));
            // the other maintenance stream is left intact
            assertFalse(old.contains(ARTIFACT + "-1.2.4.pom"));
            assertFalse(old.contains(ARTIFACT + "-1.2.4-source-release.zip"));
        }
    }

    @Test
    public void testExplicitPreviousVersionWins() throws Exception {
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS)) {
            dist.when(() -> UpdateDistCommand.listDistFiles(
                            eq(UpdateDistCommand.DIST_RELEASE_URL), eq(ARTIFACT + "-1.3.4")))
                    .thenReturn(List.of(ARTIFACT + "-1.3.4.pom"));

            List<String> old = UpdateDistCommand.listPreviousReleaseFiles(ARTIFACT, "1.3.6", "1.3.4");

            assertEquals(List.of(ARTIFACT + "-1.3.4.pom"), old);
            // when an explicit version is given, the directory is not enumerated with the bare prefix
            dist.verify(
                    () -> UpdateDistCommand.listDistFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), eq(ARTIFACT + "-")),
                    never());
        }
    }

    @Test
    public void testAutoDeduceKeepsNewVersionWithClassifierAndExtension() throws Exception {
        // files for the version being published (with both an extension '.' and a classifier '-' right
        // after the version) must be kept, exercising belongsToVersion's trailing-character check
        List<String> releaseDir = List.of(
                ARTIFACT + "-1.3.6", // exact match: filename equals the version prefix with no extension
                ARTIFACT + "-1.3.6.pom",
                ARTIFACT + "-1.3.6-source-release.zip",
                ARTIFACT + "-1.3.4.pom");
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS)) {
            dist.when(() -> UpdateDistCommand.listDistFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), anyString()))
                    .thenReturn(releaseDir);

            List<String> old = UpdateDistCommand.listPreviousReleaseFiles(ARTIFACT, "1.3.6", null);

            assertEquals(List.of(ARTIFACT + "-1.3.4.pom"), old);
            assertFalse(old.contains(ARTIFACT + "-1.3.6"));
            assertFalse(old.contains(ARTIFACT + "-1.3.6.pom"));
            assertFalse(old.contains(ARTIFACT + "-1.3.6-source-release.zip"));
        }
    }

    // ---- full command flow ----

    @Test
    public void testDryRunDescribesPublishAndRemoveWithoutCommitting() throws Exception {
        Path downloaded = downloadFolderWith(ARTIFACT + "-1.3.6-source-release.zip", ARTIFACT + "-1.3.6.pom");
        prepareRepositoryService(downloaded);
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS)) {
            dist.when(() -> UpdateDistCommand.listDistFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), anyString()))
                    .thenReturn(List.of(ARTIFACT + "-1.3.4.pom"));

            Command command = createCommand(ExecutionMode.DRY_RUN, null);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            assertTrue(logCapture.containsMessage("Would publish 2 file(s) to dist/release"));
            assertTrue(logCapture.containsMessage("Would remove 1 old file(s) from dist/release:"));
            dist.verify(() -> UpdateDistCommand.publishToDistRelease(any(), any(), any(), any(), any()), never());
        }
    }

    @Test
    public void testAutoPublishesToDistRelease() throws Exception {
        Path downloaded = downloadFolderWith(ARTIFACT + "-1.3.6-source-release.zip");
        prepareRepositoryService(downloaded);
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS)) {
            dist.when(() -> UpdateDistCommand.listDistFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), anyString()))
                    .thenReturn(List.of(ARTIFACT + "-1.3.4.pom"));
            dist.when(() -> UpdateDistCommand.publishToDistRelease(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> null);

            Command command = createCommand(ExecutionMode.AUTO, null);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            dist.verify(() -> UpdateDistCommand.publishToDistRelease(eq(ARTIFACT), eq("1.3.6"), any(), any(), any()));
        }
    }

    @Test
    public void testNoDownloadedFilesReturnsUsage() throws Exception {
        prepareRepositoryService(downloadFolderWith()); // empty download
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS)) {
            dist.when(() -> UpdateDistCommand.listDistFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), anyString()))
                    .thenReturn(List.of());

            Command command = createCommand(ExecutionMode.AUTO, null);
            assertEquals(CommandLine.ExitCode.USAGE, (int) command.call());
            dist.verify(() -> UpdateDistCommand.publishToDistRelease(any(), any(), any(), any(), any()), never());
        }
    }

    @Test
    public void testInteractiveYesPublishes() throws Exception {
        Path downloaded = downloadFolderWith(ARTIFACT + "-1.3.6-source-release.zip");
        prepareRepositoryService(downloaded);
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS);
                MockedStatic<UserInput> userInput = mockStatic(UserInput.class)) {
            dist.when(() -> UpdateDistCommand.listDistFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), anyString()))
                    .thenReturn(List.of(ARTIFACT + "-1.3.4.pom"));
            dist.when(() -> UpdateDistCommand.publishToDistRelease(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> null);
            userInput
                    .when(() -> UserInput.yesNo(anyString(), eq(InputOption.YES)))
                    .thenReturn(InputOption.YES);

            Command command = createCommand(ExecutionMode.INTERACTIVE, null);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            dist.verify(() -> UpdateDistCommand.publishToDistRelease(eq(ARTIFACT), eq("1.3.6"), any(), any(), any()));
        }
    }

    @Test
    public void testInteractiveNoAborts() throws Exception {
        Path downloaded = downloadFolderWith(ARTIFACT + "-1.3.6-source-release.zip");
        prepareRepositoryService(downloaded);
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS);
                MockedStatic<UserInput> userInput = mockStatic(UserInput.class)) {
            dist.when(() -> UpdateDistCommand.listDistFiles(eq(UpdateDistCommand.DIST_RELEASE_URL), anyString()))
                    .thenReturn(List.of(ARTIFACT + "-1.3.4.pom"));
            userInput
                    .when(() -> UserInput.yesNo(anyString(), eq(InputOption.YES)))
                    .thenReturn(InputOption.NO);

            Command command = createCommand(ExecutionMode.INTERACTIVE, null);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            assertTrue(logCapture.containsMessage("Aborted."));
            dist.verify(() -> UpdateDistCommand.publishToDistRelease(any(), any(), any(), any(), any()), never());
        }
    }

    @Test
    public void testExplicitPreviousVersionFullFlow() throws Exception {
        Path downloaded = downloadFolderWith(ARTIFACT + "-1.3.6-source-release.zip");
        prepareRepositoryService(downloaded);
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class, CALLS_REAL_METHODS)) {
            dist.when(() -> UpdateDistCommand.listDistFiles(
                            eq(UpdateDistCommand.DIST_RELEASE_URL), eq(ARTIFACT + "-1.3.4")))
                    .thenReturn(List.of(ARTIFACT + "-1.3.4.pom"));
            dist.when(() -> UpdateDistCommand.publishToDistRelease(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> null);

            Command command = createCommand(ExecutionMode.AUTO, "1.3.4");
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            dist.verify(() -> UpdateDistCommand.publishToDistRelease(eq(ARTIFACT), eq("1.3.6"), any(), any(), any()));
        }
    }

    @Test
    public void testIOExceptionReturnsSoftware() throws Exception {
        StagingRepository repository = mock(StagingRepository.class);
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.find(123)).thenReturn(repository);
        when(repositoryService.download(repository)).thenThrow(new IOException("nexus down"));
        osgiContext.registerService(RepositoryService.class, repositoryService);
        osgiContext.registerService(CredentialsService.class, mock(CredentialsService.class));

        Command command = createCommand(ExecutionMode.AUTO, null);
        assertEquals(CommandLine.ExitCode.SOFTWARE, (int) command.call());
        assertTrue(logCapture.containsMessage("Failed executing command"));
    }

    @Test
    public void testNoPomArtifactThrows() throws Exception {
        StagingRepository repository = mock(StagingRepository.class);
        LocalRepository local = mock(LocalRepository.class);
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.find(123)).thenReturn(repository);
        when(repositoryService.download(repository)).thenReturn(local);
        // a staging repository without a POM artifact triggers the orElseThrow guard
        Artifact jar = new Artifact(repository, "org.apache.sling", ARTIFACT, "1.3.6", null, "jar");
        when(local.getArtifacts()).thenReturn(Set.of(jar));
        osgiContext.registerService(RepositoryService.class, repositoryService);
        osgiContext.registerService(CredentialsService.class, mock(CredentialsService.class));

        Command command = createCommand(ExecutionMode.AUTO, null);
        try {
            command.call();
            org.junit.Assert.fail("Expected an IllegalStateException when no POM artifact is present.");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("No POM artifact"));
        }
    }

    // ---- integration tests exercising the real SVNKit operations against a temporary local repo ----

    @Test
    public void testCollectDownloadedFilesReturnsAllRegularFiles() throws Exception {
        Path root = downloadFolderWith(ARTIFACT + "-1.3.6.pom", ARTIFACT + "-1.3.6.pom.asc", ARTIFACT + "-1.3.6.jar");
        List<Path> files = UpdateDistCommand.collectDownloadedFiles(root);
        assertEquals(3, files.size());
        assertTrue(files.stream().allMatch(Files::isRegularFile));
    }

    @Test
    public void testListDistFilesAgainstLocalRepository() throws Exception {
        SVNURL repo = createLocalRepoWithReleaseFiles(
                ARTIFACT + "-1.3.6.pom", ARTIFACT + "-1.3.6-source-release.zip", "other-file.txt");
        List<String> files = UpdateDistCommand.listDistFiles(repo + "/release/", ARTIFACT + "-1.3.6");
        assertEquals(2, files.size());
        assertTrue(files.contains(ARTIFACT + "-1.3.6.pom"));
        assertTrue(files.contains(ARTIFACT + "-1.3.6-source-release.zip"));
        assertFalse(files.contains("other-file.txt"));
    }

    @Test
    public void testListDistFilesWrapsSvnFailure() {
        // a syntactically valid but non-existent local repository url triggers an SVNException,
        // which listDistFiles must surface as an IOException
        try {
            UpdateDistCommand.listDistFiles("file:///nonexistent-" + System.nanoTime() + "/release/", "x");
            org.junit.Assert.fail("Expected an IOException for a missing repository.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Failed to list"));
        }
    }

    @Test
    public void testPublishToDistReleaseAddsNewAndRemovesOld() throws Exception {
        SVNURL repo = createLocalRepoWithReleaseFiles(ARTIFACT + "-1.3.4.pom", ARTIFACT + "-1.3.4-source-release.zip");
        List<Path> newFiles = localFiles(ARTIFACT + "-1.3.6.pom", ARTIFACT + "-1.3.6-source-release.zip");

        UpdateDistCommand.publishToDistRelease(
                ARTIFACT,
                "1.3.6",
                newFiles,
                List.of(ARTIFACT + "-1.3.4.pom", ARTIFACT + "-1.3.4-source-release.zip"),
                new Credentials("johndoe", "secret"),
                repo + "/release/");

        List<String> release = listNames(repo, "release");
        assertTrue(release.contains(ARTIFACT + "-1.3.6.pom"));
        assertTrue(release.contains(ARTIFACT + "-1.3.6-source-release.zip"));
        assertFalse("the superseded release files must be removed", release.contains(ARTIFACT + "-1.3.4.pom"));
        assertFalse(release.contains(ARTIFACT + "-1.3.4-source-release.zip"));
    }

    @Test
    public void testPublishToDistReleaseWithoutOldFilesJustAdds() throws Exception {
        SVNURL repo = createLocalRepoWithReleaseFiles();
        List<Path> newFiles = localFiles(ARTIFACT + "-1.3.6.pom");

        UpdateDistCommand.publishToDistRelease(
                ARTIFACT, "1.3.6", newFiles, List.of(), new Credentials("johndoe", "secret"), repo + "/release/");

        assertTrue(listNames(repo, "release").contains(ARTIFACT + "-1.3.6.pom"));
    }

    private Path downloadFolderWith(String... names) throws IOException {
        Path root = tempFolder.newFolder("dl-" + System.nanoTime()).toPath();
        // mirror the Maven-layout sub-directories the real download produces
        Path artifactDir = Files.createDirectories(root.resolve("org/apache/sling/artifact/1.3.6"));
        for (String name : names) {
            Files.writeString(artifactDir.resolve(name), "content of " + name);
        }
        return root;
    }

    private List<Path> localFiles(String... names) throws IOException {
        Path dir = tempFolder.newFolder("src-" + System.nanoTime()).toPath();
        List<Path> files = new ArrayList<>();
        for (String name : names) {
            Path file = dir.resolve(name);
            Files.writeString(file, "content of " + name);
            files.add(file);
        }
        return files;
    }

    private SVNURL createLocalRepoWithReleaseFiles(String... releaseFiles) throws Exception {
        FSRepositoryFactory.setup();
        File dir = tempFolder.newFolder("svnrepo-" + System.nanoTime());
        SVNURL url = SVNRepositoryFactory.createLocalRepository(dir, true, false);
        SVNRepository repo = SVNRepositoryFactory.create(url);
        ISVNEditor editor = repo.getCommitEditor("set up dist layout", null);
        editor.openRoot(-1);
        editor.addDir("release", null, -1);
        SVNDeltaGenerator deltaGenerator = new SVNDeltaGenerator();
        for (String file : releaseFiles) {
            String path = "release/" + file;
            editor.addFile(path, null, -1);
            editor.applyTextDelta(path, null);
            String checksum = deltaGenerator.sendDelta(
                    path,
                    new ByteArrayInputStream(("content of " + file).getBytes(StandardCharsets.UTF_8)),
                    editor,
                    true);
            editor.closeFile(path, checksum);
        }
        editor.closeDir();
        editor.closeEdit();
        return url;
    }

    private List<String> listNames(SVNURL repoRoot, String dir) throws Exception {
        SVNRepository repo = SVNRepositoryFactory.create(repoRoot);
        Collection<SVNDirEntry> entries = new ArrayList<>();
        repo.getDir(dir, -1, null, entries);
        List<String> names = new ArrayList<>();
        entries.forEach(entry -> names.add(entry.getName()));
        return names;
    }

    private void prepareRepositoryService(Path downloadedRootFolder) throws Exception {
        StagingRepository repository = mock(StagingRepository.class);
        LocalRepository local = mock(LocalRepository.class);
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.find(123)).thenReturn(repository);
        when(repositoryService.download(repository)).thenReturn(local);
        Artifact pom = new Artifact(repository, "org.apache.sling", ARTIFACT, "1.3.6", null, "pom");
        when(local.getArtifacts()).thenReturn(Set.of(pom));
        when(local.getRootFolder()).thenReturn(downloadedRootFolder);

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
