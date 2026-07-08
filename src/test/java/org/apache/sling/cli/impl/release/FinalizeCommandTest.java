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
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.jira.VersionClient;
import org.apache.sling.cli.impl.junit.LogCapture;
import org.apache.sling.cli.impl.nexus.Artifact;
import org.apache.sling.cli.impl.nexus.LocalRepository;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.people.Member;
import org.apache.sling.cli.impl.people.MembersFinder;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.MockedStatic;
import picocli.CommandLine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FinalizeCommandTest {

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(FinalizeCommand.class);

    private RepositoryService repositoryService;
    private VersionClient versionClient;
    private CloseableHttpClient client;

    /**
     * Sets up all collaborators. {@code pmcMember} controls whether the current user is detected as
     * a PMC member, which drives the dist.apache.org step.
     */
    private void prepare(boolean pmcMember) throws Exception {
        StagingRepository stagingRepository = mock(StagingRepository.class);
        when(stagingRepository.getRepositoryId()).thenReturn("orgapachesling-123");
        when(stagingRepository.getDescription()).thenReturn("Apache Sling CLI Test 1.0.0");

        repositoryService = mock(RepositoryService.class);
        when(repositoryService.find(123)).thenReturn(stagingRepository);
        Set<Release> releases =
                Set.of(Release.fromString("Apache Sling CLI Test 1.0.0").get(0));
        when(repositoryService.getReleases(stagingRepository)).thenReturn(releases);
        Artifact pom =
                new Artifact(stagingRepository, "org.apache.sling", "org.apache.sling.cli.test", "1.0.0", null, "pom");
        LocalRepository localRepository = mock(LocalRepository.class);
        when(repositoryService.download(stagingRepository)).thenReturn(localRepository);
        when(localRepository.getArtifacts()).thenReturn(Set.of(pom));
        when(localRepository.getRootFolder()).thenReturn(java.nio.file.Path.of("target"));

        // Step 3/4 - keep JIRA interactions trivial: a successor version already exists and there are
        // no unresolved issues, so nothing is created or moved.
        versionClient = mock(VersionClient.class);
        when(versionClient.findSuccessorVersion(any())).thenReturn(mock(org.apache.sling.cli.impl.jira.Version.class));
        when(versionClient.findUnresolvedIssues(any())).thenReturn(List.of());

        // Step 5 - the reporter HTTP POST returns 200.
        HttpClientFactory httpClientFactory = mock(HttpClientFactory.class);
        client = mock(CloseableHttpClient.class);
        when(httpClientFactory.newClient()).thenReturn(client);
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(200);
        when(response.getStatusLine()).thenReturn(statusLine);
        when(client.execute(any())).thenReturn(response);

        CredentialsService credentialsService = mock(CredentialsService.class);
        when(credentialsService.getAsfCredentials()).thenReturn(new Credentials("johndoe", "secret"));

        MembersFinder membersFinder = mock(MembersFinder.class);
        when(membersFinder.getCurrentMember()).thenReturn(new Member("johndoe", "John Doe", pmcMember));

        osgiContext.registerService(RepositoryService.class, repositoryService);
        osgiContext.registerService(VersionClient.class, versionClient);
        osgiContext.registerService(HttpClientFactory.class, httpClientFactory);
        osgiContext.registerService(CredentialsService.class, credentialsService);
        osgiContext.registerService(MembersFinder.class, membersFinder);
    }

    @Test
    public void testDryRunNonPmc() throws Exception {
        prepare(false);
        Command command = createCommand(123, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        assertTrue(logCapture.containsMessage("--- Step 2/5: Update dist.apache.org --- SKIPPED (current user is not a"
                + " PMC member; a PMC member must update dist.apache.org separately) ---"));
        // dry-run: nothing is actually promoted
        verify(repositoryService, never()).promote(any());
    }

    @Test
    public void testDryRunPmc() throws Exception {
        prepare(true);
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class)) {
            dist.when(() -> UpdateDistCommand.collectDownloadedFiles(any()))
                    .thenReturn(List.of(java.nio.file.Path.of("org.apache.sling.cli.test-1.0.0.pom")));
            dist.when(() -> UpdateDistCommand.listPreviousReleaseFiles(any(), any(), any()))
                    .thenReturn(List.of("org.apache.sling.cli.test-0.9.0.pom"));
            Command command = createCommand(123, ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());
            assertTrue(logCapture.containsMessage("--- Step 2/5: Update dist.apache.org ---"));
            // dry-run: dist is described, not committed
            dist.verify(() -> UpdateDistCommand.publishToDistRelease(any(), any(), any(), any(), any()), never());
        }
    }

    @Test
    public void testAutoNonPmc() throws Exception {
        prepare(false);
        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        // promoted to Maven Central, dist skipped, jira released, reporter updated
        verify(repositoryService).promote(any());
        verify(versionClient).release(any());
        verify(client).execute(any());
        assertTrue(logCapture.containsMessage("--- Step 2/5: Update dist.apache.org --- SKIPPED (current user is not a"
                + " PMC member; a PMC member must update dist.apache.org separately) ---"));
    }

    @Test
    public void testAutoPmc() throws Exception {
        prepare(true);
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class)) {
            dist.when(() -> UpdateDistCommand.collectDownloadedFiles(any()))
                    .thenReturn(List.of(java.nio.file.Path.of("org.apache.sling.cli.test-1.0.0.pom")));
            dist.when(() -> UpdateDistCommand.listPreviousReleaseFiles(any(), any(), any()))
                    .thenReturn(List.of("org.apache.sling.cli.test-0.9.0.pom"));
            Command command = createCommand(123, ExecutionMode.AUTO);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());
            verify(repositoryService).promote(any());
            // dist upload is actually committed for a PMC member
            dist.verify(() -> UpdateDistCommand.publishToDistRelease(
                    eq("org.apache.sling.cli.test"), eq("1.0.0"), any(), any(), any()));
            verify(versionClient).release(any());
        }
    }

    @Test
    public void testReporterFailureReturnsSoftware() throws Exception {
        prepare(false);
        // the reporter POST now returns a non-200 status, which must surface as a SOFTWARE exit code
        StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(500);
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(response.getStatusLine()).thenReturn(statusLine);
        when(client.execute(any())).thenReturn(response);

        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.SOFTWARE, (int) command.call());
        assertTrue(logCapture.containsMessage("Failed executing command"));
    }

    @Test
    public void testAutoCreatesNextJiraVersionAndMovesIssues() throws Exception {
        prepare(false);
        // no successor exists yet -> the next version is created; after creation a successor is found
        org.apache.sling.cli.impl.jira.Version successor = mock(org.apache.sling.cli.impl.jira.Version.class);
        when(successor.getName()).thenReturn("CLI Test 1.0.2");
        when(versionClient.findSuccessorVersion(any())).thenReturn(null, successor);
        org.apache.sling.cli.impl.jira.Issue issue = mock(org.apache.sling.cli.impl.jira.Issue.class);
        when(versionClient.findUnresolvedIssues(any())).thenReturn(List.of(issue));

        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        verify(versionClient).create(any());
        verify(versionClient).moveIssuesToNewVersion(any(), any(), any());
    }

    @Test
    public void testDryRunDescribesNextJiraVersionAndIssues() throws Exception {
        prepare(false);
        when(versionClient.findSuccessorVersion(any())).thenReturn(null);
        org.apache.sling.cli.impl.jira.Issue issue = mock(org.apache.sling.cli.impl.jira.Issue.class);
        when(versionClient.findUnresolvedIssues(any())).thenReturn(List.of(issue));

        Command command = createCommand(123, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        // dry-run must not actually create versions or move issues
        verify(versionClient, never()).create(any());
        verify(versionClient, never()).moveIssuesToNewVersion(any(), any(), any());
        assertTrue(logCapture.containsMessage("Would create JIRA version CLI Test 1.0.2"));
    }

    private Command createCommand(int repositoryId, ExecutionMode executionMode) throws IllegalAccessException {
        FinalizeCommand finalizeCommand = spy(new FinalizeCommand());
        FieldUtils.writeField(finalizeCommand, "repositoryId", repositoryId, true);
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        FieldUtils.writeField(reusableCLIOptions, "executionMode", executionMode, true);
        FieldUtils.writeField(finalizeCommand, "reusableCLIOptions", reusableCLIOptions, true);
        osgiContext.registerInjectActivateService(finalizeCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the FinalizeCommand from the mocked OSGi environment.",
                result instanceof FinalizeCommand);
        return result;
    }
}
