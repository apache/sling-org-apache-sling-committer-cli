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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.dist.DistRepository;
import org.apache.sling.cli.impl.junit.LogCapture;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.people.Member;
import org.apache.sling.cli.impl.people.MembersFinder;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.eclipse.jgit.revwalk.RevCommit;
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
import static org.mockito.Mockito.when;

/**
 * Drives the command against a real site repository ({@link SiteRepository}); only the network services it
 * talks to - Nexus, dist.apache.org and Whimsy - are mocked.
 */
public class UpdateLocalSiteCommandTest {

    private static final String EVENT_POM = "org.apache.sling.event-4.4.2.pom";

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(UpdateLocalSiteCommand.class);

    @Rule
    public final SiteRepository site = new SiteRepository();

    /** Stubs the dist.apache.org lookup that resolves a release's artifact ids when resuming by name. */
    private MockedStatic<DistRepository> stubDist(String version) {
        MockedStatic<DistRepository> dist = mockStatic(DistRepository.class);
        dist.when(() -> DistRepository.listReleasePomFileNames(version)).thenReturn(List.of(EVENT_POM));
        return dist;
    }

    @Test
    public void testNoRepositoryNoReleaseReturnsUsage() throws Exception {
        registerServices(mock(RepositoryService.class));
        Command command = createCommand(null, null, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.USAGE, (int) command.call());
        assertTrue(logCapture.containsMessage("Provide either --repository or --release."));
    }

    @Test
    public void testDryRunEditsTheCheckoutButPushesNothing() throws Exception {
        registerServices(serviceResolving("org.apache.sling.event"));
        String upstreamBefore = site.upstreamHead().getName();

        try (MockedStatic<DistRepository> dist = stubDist("4.4.2")) {
            Command command = createCommand(null, "Apache Sling Event Impl 4.4.2", ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        }

        // the downloads entry is keyed on the artifact id even though the page calls the module "Event"
        assertTrue(downloads().contains("\"Event|org.apache.sling.event|4.4.2|"));
        assertTrue(releases().contains("Event Impl 4.4.2"));
        assertEquals(
                "a dry run must not push", upstreamBefore, site.upstreamHead().getName());
    }

    @Test
    public void testAutoCommitsAndPushesWithTheReleaseManagerAsAuthorAndCommitter() throws Exception {
        registerServices(serviceResolving("org.apache.sling.event"));

        try (MockedStatic<DistRepository> dist = stubDist("4.4.2")) {
            Command command = createCommand(null, "Apache Sling Event Impl 4.4.2", ExecutionMode.AUTO);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        }

        RevCommit head = site.upstreamHead();
        assertEquals("Released Apache Sling Event Impl 4.4.2", head.getFullMessage());
        assertEquals("John Doe", head.getAuthorIdent().getName());
        assertEquals("johndoe@apache.org", head.getAuthorIdent().getEmailAddress());
        assertTrue(site.upstreamFile("src/main/jbake/templates/downloads.tpl")
                .contains("\"Event|org.apache.sling.event|4.4.2|"));
    }

    @Test
    public void testArtifactIdsComeFromTheStagedPomsWhenARepositoryIsGiven() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        StagingRepository repository = mock(StagingRepository.class);
        when(repositoryService.find(123)).thenReturn(repository);
        when(repositoryService.getReleases(repository))
                .thenReturn(Set.copyOf(Release.fromString("Apache Sling Event Impl 4.4.2")));
        when(repositoryService.getArtifactIds(eq(repository), any())).thenReturn(Set.of("org.apache.sling.event"));
        registerServices(repositoryService);

        Command command = createCommand(123, null, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        assertTrue(downloads().contains("\"Event|org.apache.sling.event|4.4.2|"));
    }

    @Test
    public void testMaintenanceReleaseOfAnOlderMajorLeavesTheDownloadsPageAlone() throws Exception {
        // the fixture lists Resource Resolver at 1.6.6; releasing 2.0.0 must not rewrite that entry
        registerServices(serviceResolving("org.apache.sling.resourceresolver"));

        try (MockedStatic<DistRepository> dist = stubDist("2.0.0")) {
            Command command = createCommand(null, "Apache Sling Resource Resolver 2.0.0", ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        }

        assertTrue(logCapture.containsMessage("only for another major version"));
        assertTrue(downloads().contains("\"Resource Resolver|org.apache.sling.resourceresolver|1.6.6|"));
    }

    @Test
    public void testUnresolvableArtifactIdsAreReportedAsNotListed() throws Exception {
        registerServices(mock(RepositoryService.class));

        try (MockedStatic<DistRepository> dist = mockStatic(DistRepository.class)) {
            dist.when(() -> DistRepository.listReleasePomFileNames(any())).thenReturn(List.of());
            Command command = createCommand(null, "Apache Sling Foo 1.2.0", ExecutionMode.DRY_RUN);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        }

        assertTrue(logCapture.containsMessage("Could not determine the artifact id(s) for Apache Sling Foo 1.2.0"));
    }

    @Test
    public void testInteractiveDeclinedDoesNotPush() throws Exception {
        registerServices(serviceResolving("org.apache.sling.event"));
        String upstreamBefore = site.upstreamHead().getName();

        try (MockedStatic<DistRepository> dist = stubDist("4.4.2");
                MockedStatic<UserInput> input = mockStatic(UserInput.class)) {
            input.when(() -> UserInput.yesNo(any(), any())).thenReturn(InputOption.NO);
            Command command = createCommand(null, "Apache Sling Event Impl 4.4.2", ExecutionMode.INTERACTIVE);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        }

        assertEquals(
                "declining must not push", upstreamBefore, site.upstreamHead().getName());
        assertTrue(logCapture.containsMessage("Aborted; the changes are left in"));
    }

    @Test
    public void testRerunningAgainstAnUpToDatePageCommitsNothing() throws Exception {
        registerServices(serviceResolving("org.apache.sling.event"));

        try (MockedStatic<DistRepository> dist = stubDist("4.4.2")) {
            assertEquals(CommandLine.ExitCode.OK, (int)
                    createCommand(null, "Apache Sling Event Impl 4.4.2", ExecutionMode.AUTO)
                            .call());
            String afterFirst = site.upstreamHead().getName();

            // ensureRepo resets the checkout, so the second run starts from the state just pushed
            assertEquals(CommandLine.ExitCode.OK, (int)
                    createCommand(null, "Apache Sling Event Impl 4.4.2", ExecutionMode.AUTO)
                            .call());

            assertEquals(
                    "nothing further to push", afterFirst, site.upstreamHead().getName());
        }
    }

    @Test
    public void testIOExceptionReturnsSoftware() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.find(123)).thenThrow(new IOException("nexus down"));
        registerServices(repositoryService);

        Command command = createCommand(123, null, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.SOFTWARE, (int) command.call());
        assertTrue(logCapture.containsMessage("Failed executing command"));
    }

    private RepositoryService serviceResolving(String artifactId) throws IOException {
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.getArtifactIdsFromPomUrls(any(), any(), any())).thenReturn(Set.of(artifactId));
        return repositoryService;
    }

    private String downloads() throws IOException {
        return Files.readString(site.downloadsTemplate(), StandardCharsets.UTF_8);
    }

    private String releases() throws IOException {
        return Files.readString(site.releases(), StandardCharsets.UTF_8);
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
        UpdateLocalSiteCommand command = new UpdateLocalSiteCommand();
        FieldUtils.writeField(command, "repositoryId", repositoryId, true);
        FieldUtils.writeField(command, "releaseName", releaseName, true);
        ReusableCLIOptions options = new ReusableCLIOptions();
        FieldUtils.writeField(options, "executionMode", executionMode, true);
        FieldUtils.writeField(command, "reusableCLIOptions", options, true);
        SiteCheckoutOptions checkoutOptions = new SiteCheckoutOptions();
        checkoutOptions.checkout = site.checkout();
        FieldUtils.writeField(command, "siteCheckoutOptions", checkoutOptions, true);
        osgiContext.registerInjectActivateService(command);
        return command;
    }
}
