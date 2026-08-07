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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UpdateNewsCommandTest {

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(UpdateNewsCommand.class);

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

    /** Stubs out JGit so nothing is cloned, opened, reset or pushed for real. */
    private MockedStatic<Git> stubGit() {
        MockedStatic<Git> git = mockStatic(Git.class);
        Git gitInstance = mock(Git.class);
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
        DiffCommand diffCommand = mock(DiffCommand.class);
        when(diffCommand.setOutputStream(any())).thenReturn(diffCommand);
        when(gitInstance.diff()).thenReturn(diffCommand);
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

    /** Mocks the content updater, controlling whether it reports the news entry as added. */
    private MockedConstruction<JBakeContentUpdater> stubUpdater(boolean added) {
        return mockConstruction(JBakeContentUpdater.class, (m, ctx) -> when(m.updateNews(any(), any(), any(), any()))
                .thenReturn(added));
    }

    @Test
    public void testNoRepositoryNoReleaseReturnsUsage() throws Exception {
        registerServices(mock(RepositoryService.class));
        try (MockedStatic<Git> git = stubGit()) {
            Command command = createCommand(null, null, null, ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.USAGE, (int) command.call());
            assertTrue(logCapture.containsMessage("Provide either --repository or --release."));
        }
    }

    @Test
    public void testAddsEntryAndPushesInAutoMode() throws Exception {
        registerServices(mock(RepositoryService.class));
        try (MockedStatic<Git> git = stubGit();
                MockedConstruction<JBakeContentUpdater> updater = stubUpdater(true)) {
            Command command = createCommand(
                    null, "Apache Sling Foo 1.2.0", "/documentation/bundles/foo.html", ExecutionMode.AUTO);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            verify(updater.constructed().get(0))
                    .updateNews(any(), eq("Apache Sling Foo 1.2.0"), eq("/documentation/bundles/foo.html"), any());
            assertTrue(logCapture.containsMessage("Added a news entry for Apache Sling Foo 1.2.0"));
            verify(commitCommand).setMessage("Announce Apache Sling Foo 1.2.0");
            verify(pushCommand).call();
        }
    }

    @Test
    public void testWithoutLinkPassesNoLink() throws Exception {
        registerServices(mock(RepositoryService.class));
        try (MockedStatic<Git> git = stubGit();
                MockedConstruction<JBakeContentUpdater> updater = stubUpdater(true)) {
            Command command = createCommand(null, "Apache Sling Foo 1.2.0", null, ExecutionMode.AUTO);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            verify(updater.constructed().get(0)).updateNews(any(), eq("Apache Sling Foo 1.2.0"), isNull(), any());
        }
    }

    @Test
    public void testDryRunDoesNotPush() throws Exception {
        registerServices(mock(RepositoryService.class));
        try (MockedStatic<Git> git = stubGit();
                MockedConstruction<JBakeContentUpdater> updater = stubUpdater(true)) {
            Command command = createCommand(null, "Apache Sling Foo 1.2.0", null, ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            verify(pushCommand, never()).call();
        }
    }

    @Test
    public void testAlreadyAnnouncedCommitsNothing() throws Exception {
        registerServices(mock(RepositoryService.class));
        try (MockedStatic<Git> git = stubGit();
                MockedConstruction<JBakeContentUpdater> updater = stubUpdater(false)) {
            Command command = createCommand(null, "Apache Sling Foo 1.2.0", null, ExecutionMode.AUTO);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            assertTrue(logCapture.containsMessage("The news page already announces Apache Sling Foo 1.2.0"));
            assertTrue(logCapture.containsMessage("Nothing to commit."));
            verify(pushCommand, never()).call();
        }
    }

    @Test
    public void testRepositoryResolvesReleasesFromService() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        StagingRepository repository = mock(StagingRepository.class);
        when(repositoryService.find(123)).thenReturn(repository);
        when(repositoryService.getReleases(repository))
                .thenReturn(Set.copyOf(Release.fromString("Apache Sling Bar 2.0.0")));
        registerServices(repositoryService);

        try (MockedStatic<Git> git = stubGit();
                MockedConstruction<JBakeContentUpdater> updater = stubUpdater(true)) {
            Command command = createCommand(123, null, null, ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            verify(updater.constructed().get(0)).updateNews(any(), eq("Apache Sling Bar 2.0.0"), isNull(), any());
        }
    }

    @Test
    public void testIOExceptionReturnsSoftware() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.find(123)).thenThrow(new IOException("nexus down"));
        registerServices(repositoryService);

        try (MockedStatic<Git> git = stubGit()) {
            Command command = createCommand(123, null, null, ExecutionMode.DRY_RUN);
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

    private Command createCommand(Integer repositoryId, String releaseName, String link, ExecutionMode executionMode)
            throws IllegalAccessException {
        UpdateNewsCommand updateNewsCommand = spy(new UpdateNewsCommand());
        FieldUtils.writeField(updateNewsCommand, "repositoryId", repositoryId, true);
        FieldUtils.writeField(updateNewsCommand, "releaseName", releaseName, true);
        FieldUtils.writeField(updateNewsCommand, "link", link, true);
        ReusableCLIOptions options = new ReusableCLIOptions();
        FieldUtils.writeField(options, "executionMode", executionMode, true);
        FieldUtils.writeField(updateNewsCommand, "reusableCLIOptions", options, true);
        osgiContext.registerInjectActivateService(updateNewsCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the UpdateNewsCommand from the mocked OSGi environment.",
                result instanceof UpdateNewsCommand);
        return result;
    }
}
