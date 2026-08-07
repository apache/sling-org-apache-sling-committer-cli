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

import java.io.IOException;
import java.util.Set;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.jbake.JBakeContentUpdater;
import org.apache.sling.cli.impl.junit.LogCapture;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.people.Member;
import org.apache.sling.cli.impl.people.MembersFinder;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.DiffCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.StatusCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import picocli.CommandLine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UpdateLocalSiteCommandTest {

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(UpdateLocalSiteCommand.class);

    @Rule
    public final TemporaryFolder checkout = new TemporaryFolder();

    /** Keeps the site checkout inside the test's temporary folder rather than the real user's home. */
    @Before
    public void redirectCheckout() {
        System.setProperty(
                UpdateLocalSiteCommand.CHECKOUT_PROPERTY, checkout.getRoot().getAbsolutePath());
    }

    @After
    public void restoreCheckout() {
        System.clearProperty(UpdateLocalSiteCommand.CHECKOUT_PROPERTY);
    }

    private PushCommand pushCommand;
    private CommitCommand commitCommand;

    /**
     * Stubs out the JGit interactions so that no repository is cloned, opened, reset or pushed against the
     * real filesystem or network.
     */
    private MockedStatic<Git> stubGit(boolean clean) {
        MockedStatic<Git> git = mockStatic(Git.class);
        Git gitInstance = mock(Git.class);
        // ensureRepo: the checkout already exists -> fetch, then reset --hard origin/master
        ResetCommand resetCommand = mock(ResetCommand.class);
        when(resetCommand.setMode(any())).thenReturn(resetCommand);
        when(resetCommand.setRef(any())).thenReturn(resetCommand);
        when(gitInstance.reset()).thenReturn(resetCommand);
        CheckoutCommand checkoutCommand = mock(CheckoutCommand.class);
        when(checkoutCommand.setName(any())).thenReturn(checkoutCommand);
        when(gitInstance.checkout()).thenReturn(checkoutCommand);
        FetchCommand fetchCommand = mock(FetchCommand.class);
        when(fetchCommand.setProgressMonitor(any())).thenReturn(fetchCommand);
        when(fetchCommand.setDepth(anyInt())).thenReturn(fetchCommand);
        when(gitInstance.fetch()).thenReturn(fetchCommand);
        // diff(): git.diff().setOutputStream(...).call()
        DiffCommand diffCommand = mock(DiffCommand.class);
        when(diffCommand.setOutputStream(any())).thenReturn(diffCommand);
        when(gitInstance.diff()).thenReturn(diffCommand);
        // status(): drives whether there is anything to commit
        StatusCommand statusCommand = mock(StatusCommand.class);
        Status status = mock(Status.class);
        when(status.isClean()).thenReturn(clean);
        try {
            when(statusCommand.call()).thenReturn(status);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(gitInstance.status()).thenReturn(statusCommand);
        // add/commit/push
        AddCommand addCommand = mock(AddCommand.class);
        when(addCommand.addFilepattern(any())).thenReturn(addCommand);
        when(gitInstance.add()).thenReturn(addCommand);
        commitCommand = mock(CommitCommand.class);
        when(commitCommand.setMessage(any())).thenReturn(commitCommand);
        when(commitCommand.setAuthor(any(), any())).thenReturn(commitCommand);
        when(commitCommand.setCommitter(anyString(), anyString())).thenReturn(commitCommand);
        when(gitInstance.commit()).thenReturn(commitCommand);
        pushCommand = mock(PushCommand.class);
        when(pushCommand.setCredentialsProvider(any())).thenReturn(pushCommand);
        when(pushCommand.setProgressMonitor(any())).thenReturn(pushCommand);
        when(gitInstance.push()).thenReturn(pushCommand);

        git.when(() -> Git.open(any())).thenReturn(gitInstance);
        // ensureRepo: when the checkout does not yet exist, it is cloned instead
        CloneCommand cloneCommand = mock(CloneCommand.class);
        when(cloneCommand.setURI(any())).thenReturn(cloneCommand);
        when(cloneCommand.setProgressMonitor(any())).thenReturn(cloneCommand);
        when(cloneCommand.setDirectory(any())).thenReturn(cloneCommand);
        when(cloneCommand.setCloneAllBranches(anyBoolean())).thenReturn(cloneCommand);
        when(cloneCommand.setBranchesToClone(any())).thenReturn(cloneCommand);
        when(cloneCommand.setBranch(any())).thenReturn(cloneCommand);
        when(cloneCommand.setDepth(anyInt())).thenReturn(cloneCommand);
        git.when(Git::cloneRepository).thenReturn(cloneCommand);
        return git;
    }

    @Test
    public void testNoRepositoryNoReleaseReturnsUsage() throws Exception {
        registerServices(mock(RepositoryService.class));
        try (MockedStatic<Git> git = stubGit(true)) {
            Command command = createCommand(null, null, ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.USAGE, (int) command.call());
            assertTrue(logCapture.containsMessage("Provide either --repository or --release."));
        }
    }

    @Test
    public void testReleaseNameUpdatesContent() throws Exception {
        // resolving by release name only: the artifact ids come from the released POMs on dist.apache.org
        RepositoryService repositoryService = mock(RepositoryService.class);
        registerServices(repositoryService);
        try (MockedStatic<Git> git = stubGit(false);
                MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class);
                MockedConstruction<JBakeContentUpdater> updater = mockConstruction(
                        JBakeContentUpdater.class, (m, ctx) -> when(m.updateDownloadsByArtifactId(any(), any(), any()))
                                .thenReturn(new JBakeContentUpdater.DownloadsUpdate(1, 0, 0)))) {
            dist.when(() -> UpdateDistCommand.listReleasePomFileNames("1.2.0"))
                    .thenReturn(java.util.List.of("org.apache.sling.foo-1.2.0.pom"));
            when(repositoryService.getArtifactIdsFromPomUrls(any(), any(), any()))
                    .thenReturn(Set.of("org.apache.sling.foo"));

            Command command = createCommand(null, "Apache Sling Foo 1.2.0", ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            JBakeContentUpdater instance = updater.constructed().get(0);
            verify(instance).updateReleases(any(), eq("Foo"), eq("1.2.0"), any());
            verify(instance).updateDownloadsByArtifactId(any(), eq("org.apache.sling.foo"), eq("1.2.0"));
        }
    }

    @Test
    public void testRepositoryResolvesArtifactIdsFromStagedPoms() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        StagingRepository repository = mock(StagingRepository.class);
        when(repositoryService.find(123)).thenReturn(repository);
        when(repositoryService.getReleases(repository))
                .thenReturn(Set.copyOf(Release.fromString("Apache Sling Bar 2.0.0")));
        when(repositoryService.getArtifactIds(eq(repository), any())).thenReturn(Set.of("org.apache.sling.bar"));
        registerServices(repositoryService);

        try (MockedStatic<Git> git = stubGit(false);
                MockedConstruction<JBakeContentUpdater> updater = mockConstruction(
                        JBakeContentUpdater.class, (m, ctx) -> when(m.updateDownloadsByArtifactId(any(), any(), any()))
                                .thenReturn(new JBakeContentUpdater.DownloadsUpdate(1, 0, 0)))) {
            Command command = createCommand(123, null, ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            JBakeContentUpdater instance = updater.constructed().get(0);
            verify(instance, atLeastOnce()).updateDownloadsByArtifactId(any(), eq("org.apache.sling.bar"), eq("2.0.0"));
        }
    }

    @Test
    public void testDryRunDoesNotPush() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.getArtifactIdsFromPomUrls(any(), any(), any()))
                .thenReturn(Set.of("org.apache.sling.foo"));
        registerServices(repositoryService);

        try (MockedStatic<Git> git = stubGit(false);
                MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class);
                MockedConstruction<JBakeContentUpdater> updater = mockConstruction(
                        JBakeContentUpdater.class, (m, ctx) -> when(m.updateDownloadsByArtifactId(any(), any(), any()))
                                .thenReturn(new JBakeContentUpdater.DownloadsUpdate(1, 0, 0)))) {
            dist.when(() -> UpdateDistCommand.listReleasePomFileNames(any()))
                    .thenReturn(java.util.List.of("org.apache.sling.foo-1.2.0.pom"));

            Command command = createCommand(null, "Apache Sling Foo 1.2.0", ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            verify(pushCommand, never()).call();
            assertTrue(logCapture.containsMessage("Would commit the changes above to"));
        }
    }

    @Test
    public void testAutoCommitsAndPushes() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.getArtifactIdsFromPomUrls(any(), any(), any()))
                .thenReturn(Set.of("org.apache.sling.foo"));
        registerServices(repositoryService);

        try (MockedStatic<Git> git = stubGit(false);
                MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class);
                MockedConstruction<JBakeContentUpdater> updater = mockConstruction(
                        JBakeContentUpdater.class, (m, ctx) -> when(m.updateDownloadsByArtifactId(any(), any(), any()))
                                .thenReturn(new JBakeContentUpdater.DownloadsUpdate(1, 0, 0)))) {
            dist.when(() -> UpdateDistCommand.listReleasePomFileNames(any()))
                    .thenReturn(java.util.List.of("org.apache.sling.foo-1.2.0.pom"));

            Command command = createCommand(null, "Apache Sling Foo 1.2.0", ExecutionMode.AUTO);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            verify(commitCommand).setMessage("Released Apache Sling Foo 1.2.0");
            verify(pushCommand).call();
        }
    }

    @Test
    public void testNothingToCommitWhenCheckoutIsClean() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.getArtifactIdsFromPomUrls(any(), any(), any()))
                .thenReturn(Set.of("org.apache.sling.foo"));
        registerServices(repositoryService);

        try (MockedStatic<Git> git = stubGit(true);
                MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class);
                MockedConstruction<JBakeContentUpdater> updater = mockConstruction(
                        JBakeContentUpdater.class, (m, ctx) -> when(m.updateDownloadsByArtifactId(any(), any(), any()))
                                .thenReturn(new JBakeContentUpdater.DownloadsUpdate(0, 0, 0)))) {
            dist.when(() -> UpdateDistCommand.listReleasePomFileNames(any()))
                    .thenReturn(java.util.List.of("org.apache.sling.foo-1.2.0.pom"));

            Command command = createCommand(null, "Apache Sling Foo 1.2.0", ExecutionMode.AUTO);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            verify(pushCommand, never()).call();
            assertTrue(logCapture.containsMessage("already up to date"));
        }
    }

    @Test
    public void testMaintenanceReleaseOfOlderMajorLeavesDownloadsAlone() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.getArtifactIdsFromPomUrls(any(), any(), any()))
                .thenReturn(Set.of("org.apache.sling.resourceresolver"));
        registerServices(repositoryService);

        try (MockedStatic<Git> git = stubGit(false);
                MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class);
                MockedConstruction<JBakeContentUpdater> updater = mockConstruction(
                        JBakeContentUpdater.class,
                        // the page lists only the newer major, so nothing is updated but it is not "missing"
                        (m, ctx) -> when(m.updateDownloadsByArtifactId(any(), any(), any()))
                                .thenReturn(new JBakeContentUpdater.DownloadsUpdate(0, 1, 0)))) {
            dist.when(() -> UpdateDistCommand.listReleasePomFileNames(any()))
                    .thenReturn(java.util.List.of("org.apache.sling.resourceresolver-1.12.18.pom"));

            Command command = createCommand(null, "Apache Sling Resource Resolver 1.12.18", ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            assertTrue(logCapture.containsMessage("only for another major version"));
        }
    }

    @Test
    public void testCheckoutDefaultsOutsideTheTemporaryDirectory() {
        // without an override the checkout must not land in a world-writable location
        System.clearProperty(UpdateLocalSiteCommand.CHECKOUT_PROPERTY);

        String resolved = UpdateLocalSiteCommand.checkoutDir();

        assertTrue("expected a .sling-cli path but got " + resolved, resolved.endsWith("/.sling-cli/sling-site"));
        assertTrue("must not sit under /tmp", !resolved.startsWith("/tmp/"));
    }

    @Test
    public void testCheckoutHonoursTheOverride() {
        System.setProperty(UpdateLocalSiteCommand.CHECKOUT_PROPERTY, "/somewhere/else");

        assertEquals("/somewhere/else", UpdateLocalSiteCommand.checkoutDir());
    }

    @Test
    public void testUnresolvableArtifactIdsAreReportedAsNotListed() throws Exception {
        // neither the staging repository nor dist.apache.org yields an artifact id for the release
        RepositoryService repositoryService = mock(RepositoryService.class);
        registerServices(repositoryService);

        try (MockedStatic<Git> git = stubGit(false);
                MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class);
                MockedConstruction<JBakeContentUpdater> updater = mockConstruction(JBakeContentUpdater.class)) {
            dist.when(() -> UpdateDistCommand.listReleasePomFileNames(any())).thenReturn(java.util.List.of());

            Command command = createCommand(null, "Apache Sling Foo 1.2.0", ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            assertTrue(logCapture.containsMessage("Could not determine the artifact id(s) for Apache Sling Foo 1.2.0"));
            // the downloads page is never touched when the artifact is unknown
            verify(updater.constructed().get(0), never()).updateDownloadsByArtifactId(any(), any(), any());
        }
    }

    @Test
    public void testInteractiveDeclinedDoesNotPush() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.getArtifactIdsFromPomUrls(any(), any(), any()))
                .thenReturn(Set.of("org.apache.sling.foo"));
        registerServices(repositoryService);

        try (MockedStatic<Git> git = stubGit(false);
                MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class);
                MockedStatic<UserInput> input = mockStatic(UserInput.class);
                MockedConstruction<JBakeContentUpdater> updater = mockConstruction(
                        JBakeContentUpdater.class, (m, ctx) -> when(m.updateDownloadsByArtifactId(any(), any(), any()))
                                .thenReturn(new JBakeContentUpdater.DownloadsUpdate(1, 0, 0)))) {
            dist.when(() -> UpdateDistCommand.listReleasePomFileNames(any()))
                    .thenReturn(java.util.List.of("org.apache.sling.foo-1.2.0.pom"));
            input.when(() -> UserInput.yesNo(any(), any())).thenReturn(InputOption.NO);

            Command command = createCommand(null, "Apache Sling Foo 1.2.0", ExecutionMode.INTERACTIVE);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            verify(pushCommand, never()).call();
            assertTrue(logCapture.containsMessage("Aborted; the changes are left in"));
        }
    }

    @Test
    public void testAlreadyCurrentEntryIsNotReportedAsMissing() throws Exception {
        // re-running against a page that already carries the version must not claim the entry is absent
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.getArtifactIdsFromPomUrls(any(), any(), any()))
                .thenReturn(Set.of("org.apache.sling.event"));
        registerServices(repositoryService);

        try (MockedStatic<Git> git = stubGit(true);
                MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class);
                MockedConstruction<JBakeContentUpdater> updater = mockConstruction(
                        JBakeContentUpdater.class, (m, ctx) -> when(m.updateDownloadsByArtifactId(any(), any(), any()))
                                .thenReturn(new JBakeContentUpdater.DownloadsUpdate(0, 0, 1)))) {
            dist.when(() -> UpdateDistCommand.listReleasePomFileNames(any()))
                    .thenReturn(java.util.List.of("org.apache.sling.event-4.4.2.pom"));

            Command command = createCommand(null, "Apache Sling Event Impl 4.4.2", ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            assertTrue(logCapture.containsMessage("already lists"));
            assertTrue(
                    "must not warn about a missing entry",
                    !logCapture.containsMessage("has no entry for Apache Sling Event Impl 4.4.2"));
        }
    }

    @Test
    public void testIOExceptionReturnsSoftware() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.find(123)).thenThrow(new IOException("nexus down"));
        registerServices(repositoryService);

        try (MockedStatic<Git> git = stubGit(true)) {
            Command command = createCommand(123, null, ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.SOFTWARE, (int) command.call());
            assertTrue(logCapture.containsMessage("Failed executing command"));
        }
    }

    private void registerServices(RepositoryService repositoryService) {
        osgiContext.registerService(RepositoryService.class, repositoryService);

        CredentialsService credentialsService = mock(CredentialsService.class);
        when(credentialsService.getAsfCredentials()).thenReturn(new Credentials("johndoe", "secret"));
        osgiContext.registerService(CredentialsService.class, credentialsService);

        MembersFinder membersFinder = mock(MembersFinder.class);
        when(membersFinder.getCurrentMember()).thenReturn(new Member("johndoe", "John Doe", true));
        osgiContext.registerService(MembersFinder.class, membersFinder);
    }

    private Command createCommand(Integer repositoryId, String releaseName, ExecutionMode executionMode)
            throws IllegalAccessException {
        UpdateLocalSiteCommand updateLocalSiteCommand = spy(new UpdateLocalSiteCommand());
        FieldUtils.writeField(updateLocalSiteCommand, "repositoryId", repositoryId, true);
        FieldUtils.writeField(updateLocalSiteCommand, "releaseName", releaseName, true);
        ReusableCLIOptions options = new ReusableCLIOptions();
        FieldUtils.writeField(options, "executionMode", executionMode, true);
        FieldUtils.writeField(updateLocalSiteCommand, "reusableCLIOptions", options, true);
        osgiContext.registerInjectActivateService(updateLocalSiteCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the UpdateLocalSiteCommand from the mocked OSGi environment.",
                result instanceof UpdateLocalSiteCommand);
        return result;
    }
}
