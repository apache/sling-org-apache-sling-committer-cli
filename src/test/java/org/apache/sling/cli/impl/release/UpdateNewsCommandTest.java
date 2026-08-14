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
import java.util.Set;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.junit.LogCapture;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.people.Member;
import org.apache.sling.cli.impl.people.MembersFinder;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Rule;
import org.junit.Test;
import picocli.CommandLine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Drives the command against a real site repository ({@link SiteRepository}) rather than mocking JGit. */
public class UpdateNewsCommandTest {

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(UpdateNewsCommand.class);

    @Rule
    public final SiteRepository site = new SiteRepository();

    @Test
    public void testNoRepositoryNoReleaseReturnsUsage() throws Exception {
        registerServices(mock(RepositoryService.class));
        Command command = createCommand(null, null, null, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.USAGE, (int) command.call());
        assertTrue(logCapture.containsMessage("Provide either --repository or --release."));
    }

    @Test
    public void testAddsALinkedEntryAndPushesInAutoMode() throws Exception {
        registerServices(mock(RepositoryService.class));

        Command command =
                createCommand(null, "Apache Sling Foo 1.2.0", "/documentation/bundles/foo.html", ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        assertTrue(logCapture.containsMessage("Added a news entry for Apache Sling Foo 1.2.0"));
        String news = site.upstreamFile("src/main/jbake/content/news.md");
        assertTrue(
                "the entry should link to the given page",
                news.contains("* Released [Apache Sling Foo 1.2.0](/documentation/bundles/foo.html)"));

        RevCommit head = site.upstreamHead();
        assertEquals("Announce Apache Sling Foo 1.2.0", head.getFullMessage());
        assertEquals("johndoe@apache.org", head.getAuthorIdent().getEmailAddress());
    }

    @Test
    public void testWithoutALinkTheEntryIsPlainText() throws Exception {
        registerServices(mock(RepositoryService.class));

        Command command = createCommand(null, "Apache Sling Foo 1.2.0", null, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        assertTrue(site.upstreamFile("src/main/jbake/content/news.md").contains("* Released Apache Sling Foo 1.2.0 ("));
    }

    @Test
    public void testDryRunDoesNotPush() throws Exception {
        registerServices(mock(RepositoryService.class));
        String upstreamBefore = site.upstreamHead().getName();

        Command command = createCommand(null, "Apache Sling Foo 1.2.0", null, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        assertEquals(
                "a dry run must not push", upstreamBefore, site.upstreamHead().getName());
        // the entry is still written to the checkout so the diff can be reviewed
        assertTrue(
                Files.readString(site.checkoutPath().resolve("src/main/jbake/content/news.md"), StandardCharsets.UTF_8)
                        .contains("Apache Sling Foo 1.2.0"));
    }

    @Test
    public void testAnAlreadyAnnouncedReleaseCommitsNothing() throws Exception {
        registerServices(mock(RepositoryService.class));
        assertEquals(
                CommandLine.ExitCode.OK, (int) createCommand(null, "Apache Sling Foo 1.2.0", null, ExecutionMode.AUTO)
                        .call());
        String afterFirst = site.upstreamHead().getName();

        assertEquals(
                CommandLine.ExitCode.OK, (int) createCommand(null, "Apache Sling Foo 1.2.0", null, ExecutionMode.AUTO)
                        .call());

        assertTrue(logCapture.containsMessage("The news page already announces Apache Sling Foo 1.2.0"));
        assertTrue(logCapture.containsMessage("Nothing to commit."));
        assertEquals("nothing further to push", afterFirst, site.upstreamHead().getName());
    }

    @Test
    public void testRepositoryResolvesReleasesFromService() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        StagingRepository repository = mock(StagingRepository.class);
        when(repositoryService.find(123)).thenReturn(repository);
        when(repositoryService.getReleases(repository))
                .thenReturn(Set.copyOf(Release.fromString("Apache Sling Bar 2.0.0")));
        registerServices(repositoryService);

        Command command = createCommand(123, null, null, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        assertTrue(site.upstreamFile("src/main/jbake/content/news.md").contains("Apache Sling Bar 2.0.0"));
    }

    @Test
    public void testIOExceptionReturnsSoftware() throws Exception {
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.find(123)).thenThrow(new IOException("nexus down"));
        registerServices(repositoryService);

        Command command = createCommand(123, null, null, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.SOFTWARE, (int) command.call());
        assertTrue(logCapture.containsMessage("Failed executing command"));
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
        UpdateNewsCommand command = new UpdateNewsCommand();
        FieldUtils.writeField(command, "repositoryId", repositoryId, true);
        FieldUtils.writeField(command, "releaseName", releaseName, true);
        FieldUtils.writeField(command, "link", link, true);
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
