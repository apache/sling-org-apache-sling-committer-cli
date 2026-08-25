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
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.dist.DistRepository;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.MockedStatic;
import picocli.CommandLine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
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

    private StagingRepository stagingRepository;
    private RepositoryService repositoryService;
    private VersionClient versionClient;
    private CloseableHttpClient client;
    private MockedStatic<UpdateLocalSiteCommand> site;

    /**
     * Stubs out the website step for every test. Without this the step would clone the real sling-site
     * repository and attempt an actual push to gitbox; because the step deliberately only warns on failure,
     * that would go unnoticed instead of failing the build.
     */
    @Before
    public void stubSiteStep() {
        site = mockStatic(UpdateLocalSiteCommand.class);
        site.when(() -> UpdateLocalSiteCommand.updateLocalSite(any(), any(), any(), any()))
                .thenReturn(new UpdateLocalSiteCommand.SiteUpdate(true, "Apache Sling CLI Test 1.0.0", List.of()));
    }

    @After
    public void releaseSiteStep() {
        site.close();
    }

    /**
     * Sets up all collaborators. {@code pmcMember} controls whether the current user is detected as
     * a PMC member, which drives the dist.apache.org step.
     */
    private void prepare(boolean pmcMember) throws Exception {
        stagingRepository = mock(StagingRepository.class);
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
        assertTrue(logCapture.containsMessage("--- Step 1/6: Update dist.apache.org --- SKIPPED (current user is not a"
                + " PMC member; a PMC member must update dist.apache.org separately) ---"));
        // dry-run: nothing is actually promoted
        verify(repositoryService, never()).promote(any());
    }

    @Test
    public void testDryRunPmc() throws Exception {
        prepare(true);
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class);
                MockedStatic<DistRepository> distRepo = mockStatic(DistRepository.class)) {
            dist.when(() -> UpdateDistCommand.planDistRelease(any(), any(), any()))
                    .thenReturn(new UpdateDistCommand.DistReleasePlan(
                            "org.apache.sling.cli.test",
                            "1.0.0",
                            List.of(java.nio.file.Path.of("org.apache.sling.cli.test-1.0.0.pom")),
                            List.of("org.apache.sling.cli.test-0.9.0.pom"),
                            false));
            Command command = createCommand(123, ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());
            assertTrue(logCapture.containsMessage("--- Step 1/6: Update dist.apache.org ---"));
            // dry-run: dist is described, not committed
            distRepo.verify(() -> DistRepository.publish(any(), any(), any(), any(), any()), never());
        }
    }

    @Test
    public void testAutoNonPmc() throws Exception {
        prepare(false);
        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        // promoted to Maven Central, dist skipped, jira released, reporter updated
        verify(repositoryService).promote(any());
        verify(versionClient).release(any(), any());
        // reporter now queries (GET overview) before posting (POST addrelease)
        verify(client, atLeastOnce()).execute(any());
        assertTrue(logCapture.containsMessage("--- Step 1/6: Update dist.apache.org --- SKIPPED (current user is not a"
                + " PMC member; a PMC member must update dist.apache.org separately) ---"));
    }

    @Test
    public void testAutoPmc() throws Exception {
        prepare(true);
        try (MockedStatic<UpdateDistCommand> dist = mockStatic(UpdateDistCommand.class);
                MockedStatic<DistRepository> distRepo = mockStatic(DistRepository.class)) {
            dist.when(() -> UpdateDistCommand.planDistRelease(any(), any(), any()))
                    .thenReturn(new UpdateDistCommand.DistReleasePlan(
                            "org.apache.sling.cli.test",
                            "1.0.0",
                            List.of(java.nio.file.Path.of("org.apache.sling.cli.test-1.0.0.pom")),
                            List.of("org.apache.sling.cli.test-0.9.0.pom"),
                            false));
            Command command = createCommand(123, ExecutionMode.AUTO);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());
            verify(repositoryService).promote(any());
            // dist upload is actually committed for a PMC member
            distRepo.verify(
                    () -> DistRepository.publish(eq("org.apache.sling.cli.test"), eq("1.0.0"), any(), any(), any()));
            verify(versionClient).release(any(), any());
        }
    }

    @Test
    public void testSiteUpdateIsDelegatedToUpdateLocalSite() throws Exception {
        prepare(false); // non-PMC: the dist step is skipped, so this test needs no network
        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        assertTrue(logCapture.containsMessage("--- Step 6/6: Update the Sling website ---"));
        // finalize orchestrates rather than reimplements: the editing and the commit/push both come from
        // UpdateLocalSiteCommand
        site.verify(() -> UpdateLocalSiteCommand.updateLocalSite(any(), any(), any(), any()));
        site.verify(() -> UpdateLocalSiteCommand.applySiteUpdate(any(), any(), eq(ExecutionMode.AUTO), any(), any()));
    }

    @Test
    public void testSiteUpdateDoesNotUseTheRepositoryPromoteJustDropped() throws Exception {
        prepare(false); // non-PMC: the dist step is skipped, so this test needs no network
        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        // Nexus drops the staging repository on release, so step 6 must resolve the artifact ids from
        // dist.apache.org instead of searching a repository that is gone (SLING-13320)
        site.verify(() -> UpdateLocalSiteCommand.updateLocalSite(any(), isNull(), any(), any()));
    }

    @Test
    public void testDryRunKeepsTheRepositoryForTheSiteStep() throws Exception {
        prepare(false);
        // nothing was promoted, so the staged POMs are still the best source for the artifact ids
        Command command = createCommand(123, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        site.verify(() -> UpdateLocalSiteCommand.updateLocalSite(any(), eq(stagingRepository), any(), any()));
    }

    @Test
    public void testSiteUpdateRuntimeFailureDoesNotFailFinalize() throws Exception {
        prepare(false);
        // an unchecked exception escaping step 6 used to fail the whole run after everything irreversible
        // had already succeeded (SLING-13320)
        site.when(() -> UpdateLocalSiteCommand.updateLocalSite(any(), any(), any(), any()))
                .thenThrow(new com.google.gson.JsonSyntaxException("malformed JSON"));

        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        assertTrue(logCapture.containsMessage("Failed to update the Sling website"));
    }

    @Test
    public void testSiteUpdateFailureDoesNotFailFinalize() throws Exception {
        prepare(false); // non-PMC: the dist step is skipped, so this test needs no network
        // everything irreversible has already succeeded by step 6, so a website hiccup must not fail the run
        site.when(() -> UpdateLocalSiteCommand.updateLocalSite(any(), any(), any(), any()))
                .thenThrow(new java.io.IOException("gitbox down"));

        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        assertTrue(logCapture.containsMessage("Failed to update the Sling website"));
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
    public void testReporterReturns200WithErrorBodyFails() throws Exception {
        prepare(false);
        // addrelease.py returns HTTP 200 even on failure; a generic error body must surface as a failure
        StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(200);
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(response.getStatusLine()).thenReturn(statusLine);
        when(response.getEntity()).thenReturn(new StringEntity("Could not save. Unexpected server error."));
        when(client.execute(any())).thenReturn(response);

        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.SOFTWARE, (int) command.call());
        assertTrue(logCapture.containsMessage("Failed executing command"));
        // must NOT have falsely reported success
        assertTrue(logCapture.getMessages().stream().noneMatch(m -> m.contains("Updated Apache Reporter")));
    }

    @Test
    public void testReporterCommitteeAccessErrorWarnsButSucceeds() throws Exception {
        prepare(false);
        // the real reporter behaviour for a user without committee access: HTTP 200 + this message
        StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(200);
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(response.getStatusLine()).thenReturn(statusLine);
        when(response.getEntity())
                .thenReturn(new StringEntity("Could not save. Make sure you have filled out all fields and have"
                        + " access to this committee data!"));
        when(client.execute(any())).thenReturn(response);

        Command command = createCommand(123, ExecutionMode.AUTO);
        // a committee-access error is a known limitation (non-PMC), so it warns and finalize still completes
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        assertTrue(logCapture.containsMessage("lacks committee access"));
        assertTrue(logCapture.getMessages().stream().noneMatch(m -> m.contains("Updated Apache Reporter")));
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

    @Test
    public void testPreflightAbortsBeforePromoteWhenIssueTaggedAfterStaging() throws Exception {
        prepare(false);
        StagingRepository repository = repositoryService.find(123);
        when(repository.getCreated()).thenReturn(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        org.apache.sling.cli.impl.jira.Issue late = mock(org.apache.sling.cli.impl.jira.Issue.class);
        when(late.getKey()).thenReturn("SLING-13260");
        when(late.getSummary()).thenReturn("Late tagged issue");
        when(versionClient.findIssuesFixVersionedAfter(any(), any())).thenReturn(List.of(late));

        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.SOFTWARE, (int) command.call());

        assertTrue(logCapture.containsMessage("Refusing to release JIRA version Apache Sling CLI Test 1.0.0"));
        assertTrue(logCapture.containsMessage("SLING-13260"));
        assertTrue(logCapture.containsMessage("Aborting finalize before any changes are made."));
        // the whole point: nothing irreversible ran, so the operator can fix JIRA and re-run
        verify(repositoryService, never()).promote(any());
        verify(versionClient, never()).release(any(), any());
        assertTrue(logCapture.getMessages().stream().noneMatch(m -> m.contains("Step 1/5")));
    }

    @Test
    public void testForceProceedsWhenIssueTaggedAfterStaging() throws Exception {
        prepare(false);
        StagingRepository repository = repositoryService.find(123);
        when(repository.getCreated()).thenReturn(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        org.apache.sling.cli.impl.jira.Issue late = mock(org.apache.sling.cli.impl.jira.Issue.class);
        when(late.getKey()).thenReturn("SLING-13260");
        when(late.getSummary()).thenReturn("Late tagged issue");
        when(versionClient.findIssuesFixVersionedAfter(any(), any())).thenReturn(List.of(late));

        Command command = createCommand(123, ExecutionMode.AUTO, true);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        assertTrue(logCapture.containsMessage("closing them anyway because --force-close-late-issues was given"));
        // finalize proceeds through promote and release, forcing skips the guard via a null staged-at timestamp
        verify(repositoryService).promote(any());
        verify(versionClient).release(any(), isNull());
        assertTrue(logCapture.containsMessage("Marked JIRA version Apache Sling CLI Test 1.0.0 as released"));
    }

    @Test
    public void testResumeByNameSkipsPromoteAndDist() throws Exception {
        prepare(true);
        Command command = createCommandByName("Apache Sling CLI Test 1.0.0", ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        assertTrue(logCapture.containsMessage("Resuming by release name"));
        // the staging repository is gone, so promote/dist are skipped as already done...
        verify(repositoryService, never()).find(anyInt());
        verify(repositoryService, never()).promote(any());
        assertTrue(logCapture.containsMessage("SKIPPED (staging repository already promoted and dropped)"));
        // ...but the repository-independent JIRA release still runs (with no staging timestamp)
        verify(versionClient).release(any(), isNull());
    }

    private Command createCommand(int repositoryId, ExecutionMode executionMode) throws IllegalAccessException {
        return createCommand(repositoryId, executionMode, false);
    }

    private Command createCommandByName(String releaseName, ExecutionMode executionMode) throws IllegalAccessException {
        FinalizeCommand finalizeCommand = spy(new FinalizeCommand());
        FieldUtils.writeField(finalizeCommand, "releaseName", releaseName, true);
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        FieldUtils.writeField(reusableCLIOptions, "executionMode", executionMode, true);
        FieldUtils.writeField(finalizeCommand, "reusableCLIOptions", reusableCLIOptions, true);
        SiteCheckoutOptions siteCheckoutOptions = new SiteCheckoutOptions();
        siteCheckoutOptions.checkout = "target/unused-site-checkout";
        FieldUtils.writeField(finalizeCommand, "siteCheckoutOptions", siteCheckoutOptions, true);
        osgiContext.registerInjectActivateService(finalizeCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the FinalizeCommand from the mocked OSGi environment.",
                result instanceof FinalizeCommand);
        return result;
    }

    private Command createCommand(int repositoryId, ExecutionMode executionMode, boolean forceCloseLateIssues)
            throws IllegalAccessException {
        FinalizeCommand finalizeCommand = spy(new FinalizeCommand());
        FieldUtils.writeField(finalizeCommand, "repositoryId", repositoryId, true);
        FieldUtils.writeField(finalizeCommand, "forceCloseLateIssues", forceCloseLateIssues, true);
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        FieldUtils.writeField(reusableCLIOptions, "executionMode", executionMode, true);
        FieldUtils.writeField(finalizeCommand, "reusableCLIOptions", reusableCLIOptions, true);
        SiteCheckoutOptions siteCheckoutOptions = new SiteCheckoutOptions();
        siteCheckoutOptions.checkout = "target/unused-site-checkout";
        FieldUtils.writeField(finalizeCommand, "siteCheckoutOptions", siteCheckoutOptions, true);
        osgiContext.registerInjectActivateService(finalizeCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the FinalizeCommand from the mocked OSGi environment.",
                result instanceof FinalizeCommand);
        return result;
    }
}
