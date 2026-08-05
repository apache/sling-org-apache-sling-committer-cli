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
import org.apache.sling.cli.impl.jbake.JBakeContentUpdater;
import org.apache.sling.cli.impl.junit.LogCapture;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.DiffCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import picocli.CommandLine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UpdateLocalSiteCommandTest {

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(UpdateLocalSiteCommand.class);

    /**
     * Stubs out the JGit interactions so that no repository is cloned, opened or reset against the
     * real filesystem or network. The {@code Git} instance returned by {@code Git.open(...)} is a
     * mock whose {@code diff()} returns a mock {@link DiffCommand}.
     */
    private MockedStatic<Git> stubGit() {
        MockedStatic<Git> git = mockStatic(Git.class);
        Git gitInstance = mock(Git.class);
        // ensureRepo: the checkout already exists -> Git.open(...).reset()...call()
        ResetCommand resetCommand = mock(ResetCommand.class);
        when(resetCommand.setMode(any())).thenReturn(resetCommand);
        when(gitInstance.reset()).thenReturn(resetCommand);
        // call(): git.diff().setOutputStream(...).call()
        DiffCommand diffCommand = mock(DiffCommand.class);
        when(diffCommand.setOutputStream(any())).thenReturn(diffCommand);
        when(gitInstance.diff()).thenReturn(diffCommand);
        git.when(() -> Git.open(any())).thenReturn(gitInstance);
        // ensureRepo: when the checkout does not yet exist, it is cloned instead
        CloneCommand cloneCommand = mock(CloneCommand.class);
        when(cloneCommand.setURI(any())).thenReturn(cloneCommand);
        when(cloneCommand.setProgressMonitor(any())).thenReturn(cloneCommand);
        when(cloneCommand.setDirectory(any())).thenReturn(cloneCommand);
        git.when(Git::cloneRepository).thenReturn(cloneCommand);
        return git;
    }

    @Test
    public void testNoRepositoryNoReleaseReturnsUsage() throws Exception {
        osgiContext.registerService(RepositoryService.class, mock(RepositoryService.class));
        try (MockedStatic<Git> git = stubGit()) {
            Command command = createCommand(null, null);
            assertEquals(CommandLine.ExitCode.USAGE, (int) command.call());
            assertTrue(logCapture.containsMessage("Provide either --repository or --release."));
        }
    }

    @Test
    public void testReleaseNameUpdatesContent() throws Exception {
        osgiContext.registerService(RepositoryService.class, mock(RepositoryService.class));
        try (MockedStatic<Git> git = stubGit();
                MockedConstruction<JBakeContentUpdater> updater = mockConstruction(JBakeContentUpdater.class)) {
            Command command = createCommand(null, "Apache Sling Foo 1.2.0");
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            JBakeContentUpdater instance = updater.constructed().get(0);
            verify(instance).updateDownloads(any(), eq("Foo"), eq("1.2.0"));
            verify(instance).updateReleases(any(), eq("Foo"), eq("1.2.0"), any());
        }
    }

    @Test
    public void testRepositoryResolvesReleasesFromService() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        StagingRepository repository = mock(StagingRepository.class);
        when(repositoryService.find(123)).thenReturn(repository);
        when(repositoryService.getReleases(repository))
                .thenReturn(Set.copyOf(Release.fromString("Apache Sling Bar 2.0.0")));
        osgiContext.registerService(RepositoryService.class, repositoryService);

        try (MockedStatic<Git> git = stubGit();
                MockedConstruction<JBakeContentUpdater> updater = mockConstruction(JBakeContentUpdater.class)) {
            Command command = createCommand(123, null);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());

            JBakeContentUpdater instance = updater.constructed().get(0);
            verify(instance, atLeastOnce()).updateDownloads(any(), eq("Bar"), eq("2.0.0"));
        }
    }

    @Test
    public void testIOExceptionReturnsSoftware() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.find(123)).thenThrow(new IOException("nexus down"));
        osgiContext.registerService(RepositoryService.class, repositoryService);

        try (MockedStatic<Git> git = stubGit()) {
            Command command = createCommand(123, null);
            assertEquals(CommandLine.ExitCode.SOFTWARE, (int) command.call());
            assertTrue(logCapture.containsMessage("Failed executing command"));
        }
    }

    private Command createCommand(Integer repositoryId, String releaseName) throws IllegalAccessException {
        UpdateLocalSiteCommand updateLocalSiteCommand = spy(new UpdateLocalSiteCommand());
        FieldUtils.writeField(updateLocalSiteCommand, "repositoryId", repositoryId, true);
        FieldUtils.writeField(updateLocalSiteCommand, "releaseName", releaseName, true);
        osgiContext.registerInjectActivateService(updateLocalSiteCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the UpdateLocalSiteCommand from the mocked OSGi environment.",
                result instanceof UpdateLocalSiteCommand);
        return result;
    }
}
