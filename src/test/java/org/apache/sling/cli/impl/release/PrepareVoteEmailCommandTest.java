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
package org.apache.sling.cli.impl.release;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.DateProvider;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.jira.Issue;
import org.apache.sling.cli.impl.jira.Version;
import org.apache.sling.cli.impl.jira.VersionClient;
import org.apache.sling.cli.impl.mail.Mailer;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.people.Member;
import org.apache.sling.cli.impl.people.MembersFinder;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Rule;
import org.junit.Test;
import org.osgi.framework.ServiceReference;
import org.powermock.reflect.Whitebox;

import picocli.CommandLine;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PrepareVoteEmailCommandTest {

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Test
    public void testPrepareEmailGeneration() throws Exception {
        Mailer mailer = mock(Mailer.class);
        prepareExecution(mailer);
        PrepareVoteEmailCommand prepareVoteEmailCommand = spy(new PrepareVoteEmailCommand());
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        CommandLine.Model.CommandSpec commandSpec = mock(CommandLine.Model.CommandSpec.class);
        CommandLine commandLine = mock(CommandLine.class);
        when(commandSpec.commandLine()).thenReturn(commandLine);
        when(commandLine.isUsageHelpRequested()).thenReturn(false);
        Whitebox.setInternalState(prepareVoteEmailCommand, "spec", commandSpec);
        Whitebox.setInternalState(reusableCLIOptions, "executionMode", ExecutionMode.AUTO);
        Whitebox.setInternalState(prepareVoteEmailCommand, "reusableCLIOptions", reusableCLIOptions);
        Whitebox.setInternalState(prepareVoteEmailCommand, "repositoryId", 123);
        osgiContext.registerInjectActivateService(prepareVoteEmailCommand);

        ServiceReference<?> reference =
                osgiContext.bundleContext().getServiceReference(Command.class.getName());
        Command command = (Command) osgiContext.bundleContext().getService(reference);
        assertEquals(CommandLine.ExitCode.OK, (int)command.call());
        verify(mailer).send(
                "From: John Doe <johndoe@apache.org>\n" +
                        "To: \"Sling Developers List\" <dev@sling.apache.org>\n" +
                        "Reply-To: \"Sling Developers List\" <dev@sling.apache.org>\n" +
                        "Date: Thu, 1 Jan 1970 01:00:00 +0100\n" +
                        "Subject: [VOTE] Release Apache Sling CLI Test 1.0.0\n" +
                        "\n" +
                        "Hi,\n" +
                        "\n" +
                        "We solved 42 issues in this release:\n" +
                        "https://issues.apache.org/jira/browse/SLING/fixforversion/1\n" +
                        "\n" +
                        "Staging repository:\n" +
                        "https://repository.apache.org/content/repositories/orgapachesling-123/\n" +
                        "\n" +
                        "You can use this UNIX script to download the release and verify the signatures:\n" +
                        "https://gitbox.apache.org/repos/asf?p=sling-tooling-release.git;a=blob;f=check_staged_release.sh;hb=HEAD\n" +
                        "\n" +
                        "Usage:\n" +
                        "sh check_staged_release.sh 123 /tmp/sling-staging\n" +
                        "\n" +
                        "Please vote to approve this release:\n" +
                        "\n" +
                        "  [ ] +1 Approve the release\n" +
                        "  [ ]  0 Don't care\n" +
                        "  [ ] -1 Don't release, because ...\n" +
                        "\n" +
                        "This majority vote is open for at least 72 hours.\n" +
                        "\n" +
                        "Regards,\n" +
                        "John Doe\n");
    }

    private void prepareExecution(Mailer mailer) throws IOException {
        MembersFinder membersFinder = mock(MembersFinder.class);
        when(membersFinder.getCurrentMember()).thenReturn(new Member("johndoe", "John Doe", true));

        StagingRepository stagingRepository = mock(StagingRepository.class);
        when(stagingRepository.getDescription()).thenReturn("Apache Sling CLI Test 1.0.0");
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.find(123)).thenReturn(stagingRepository);

        VersionClient versionClient = mock(VersionClient.class);
        Version version = mock(Version.class);
        when(version.getName()).thenReturn("CLI Test 1.0.0");
        when(version.getId()).thenReturn(1);
        when(version.getIssuesFixedCount()).thenReturn(42);
        Release release = Release.fromString("Apache Sling CLI Test 1.0.0").get(0);
        when(repositoryService.getReleases(stagingRepository)).thenReturn(Set.of(release));
        List<Issue> fixedIssues = new ArrayList<>();
        for (int i = 0; i < 42; i++) {
            fixedIssues.add(mock(Issue.class));
        }
        when(versionClient.findFixedIssues(release)).thenReturn(fixedIssues);
        when(versionClient.find(release)).thenReturn(version);

        DateProvider dateProvider = mock(DateProvider.class);
        when(dateProvider.getCurrentDateForEmailHeader()).thenReturn("Thu, 1 Jan 1970 01:00:00 +0100");
        osgiContext.registerService(DateProvider.class, dateProvider);

        osgiContext.registerService(MembersFinder.class, membersFinder);
        osgiContext.registerService(RepositoryService.class, repositoryService);
        osgiContext.registerService(VersionClient.class, versionClient);
        osgiContext.registerService(Mailer.class, mailer);
    }
}
