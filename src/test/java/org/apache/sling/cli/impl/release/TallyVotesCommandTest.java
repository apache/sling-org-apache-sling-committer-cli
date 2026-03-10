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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.mail.internet.InternetAddress;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.DateProvider;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.junit.LogCapture;
import org.apache.sling.cli.impl.mail.Email;
import org.apache.sling.cli.impl.mail.Mailer;
import org.apache.sling.cli.impl.mail.VoteThreadFinder;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.people.Member;
import org.apache.sling.cli.impl.people.MembersFinder;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.osgi.framework.ServiceReference;

import picocli.CommandLine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class TallyVotesCommandTest {

    @Before
    public void beforeClass() {
        DateProvider dateProvider = mock(DateProvider.class);
        when(dateProvider.getCurrentDateForEmailHeader()).thenReturn("Thu, 1 Jan 1970 01:00:00 +0100");
        osgiContext.registerService(DateProvider.class, dateProvider);
    }

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(TallyVotesCommand.class);

    @Test
    public void testDryRun() throws Exception {
        Mailer mailer = mock(Mailer.class);
        List<Email> thread = new ArrayList<>(){{
            add(mockEmail("johndoe@apache.org", "John Doe"));
            add(mockEmail("alice@apache.org", "Alice"));
            add(mockEmail("bob@apache.org", "Bob"));
            add(mockEmail("charlie@apache.org", "Charlie"));
            add(mockEmail("daniel@apache.org", "Daniel"));
            add(mockEmail("johndoe@apache.org", "John Doe"));
            add(mockEmail("jhoh228@googlemail.com.INVALID", "Jörg Hoh"));
        }};
        prepareExecution(mailer, thread);
        Command command = createCommand(123, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.OK, (int)command.call());
        verifyNoInteractions(mailer);
        assertTrue(logCapture.containsMessage(
                "The following email would be sent from your @apache.org address (see the \"From:\" header):"));
        assertTrue(logCapture.containsMessage("From: John Doe <johndoe@apache.org>\n" +
                "To: \"Sling Developers List\" <dev@sling.apache.org>\n" +
                "Reply-To: \"Sling Developers List\" <dev@sling.apache.org>\n" +
                "Date: Thu, 1 Jan 1970 01:00:00 +0100\n" +
                "Subject: [RESULT] [VOTE] Release Apache Sling CLI Test 1.0.0\n" +
                "\n" +
                "Hi,\n" +
                "\n" +
                "The vote has passed with the following result:\n" +
                "\n" +
                "+1 (binding): Alice, Bob, Charlie, John Doe, Joerg Hoh\n" +
                "+1 (non-binding): Daniel\n" +
                "\n" +
                "I will copy this release to the Sling dist directory and\n" +
                "promote the artifacts to the central Maven repository.\n" +
                "\n" +
                "Regards,\n" +
                "John Doe\n"));
    }

    @Test
    public void testDryRunNotEnoughBindingVotes() throws Exception {
        Mailer mailer = mock(Mailer.class);
        List<Email> thread = new ArrayList<>(){{
            add(mockEmail("johndoe@apache.org", "John Doe"));
            add(mockEmail("alice@apache.org", "Alice"));
            add(mockEmail("bob@apache.org", "Bob"));
            add(mockEmail("daniel@apache.org", "Daniel"));
        }};
        prepareExecution(mailer, thread);
        Command command = createCommand(123, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.USAGE, (int)command.call());
        verifyNoInteractions(mailer);
        assertTrue(logCapture.containsMessage(
                "Release Apache Sling CLI Test 1.0.0 does not have at least 3 binding votes."));

    }

    @Test
    public void testAuto() throws Exception {
        List<Email> thread = new ArrayList<>(){{
            add(mockEmail("johndoe@apache.org", "John Doe"));
            add(mockEmail("alice@apache.org", "Alice"));
            add(mockEmail("bob@apache.org", "Bob"));
            add(mockEmail("charlie@apache.org", "Charlie"));
            add(mockEmail("daniel@apache.org", "Daniel"));
            add(mockEmail("johndoe@apache.org", "John Doe"));
        }};
        Mailer mailer = mock(Mailer.class);
        prepareExecution(mailer, thread);
        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int)command.call());
        verify(mailer).send(
                "From: John Doe <johndoe@apache.org>\n" +
                        "To: \"Sling Developers List\" <dev@sling.apache.org>\n" +
                        "Reply-To: \"Sling Developers List\" <dev@sling.apache.org>\n" +
                        "Date: Thu, 1 Jan 1970 01:00:00 +0100\n" +
                        "Subject: [RESULT] [VOTE] Release Apache Sling CLI Test 1.0.0\n" +
                        "\n" +
                        "Hi,\n" +
                        "\n" +
                        "The vote has passed with the following result:\n" +
                        "\n" +
                        "+1 (binding): Alice, Bob, Charlie, John Doe\n" +
                        "+1 (non-binding): Daniel\n" +
                        "\n" +
                        "I will copy this release to the Sling dist directory and\n" +
                        "promote the artifacts to the central Maven repository.\n" +
                        "\n" +
                        "Regards,\n" +
                        "John Doe\n"
        );
    }

    private Command createCommand(int repositoryId, ExecutionMode executionMode) throws IllegalAccessException {
        TallyVotesCommand tallyVotesCommand = spy(new TallyVotesCommand());
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        FieldUtils.writeField(reusableCLIOptions, "executionMode", executionMode, true);
        FieldUtils.writeField(tallyVotesCommand, "repositoryId", repositoryId, true);
        FieldUtils.writeField(tallyVotesCommand, "reusableCLIOptions", reusableCLIOptions, true);
        osgiContext.registerInjectActivateService(tallyVotesCommand);
        ServiceReference<?> reference =
                osgiContext.bundleContext().getServiceReference(Command.class.getName());
        return (Command) osgiContext.bundleContext().getService(reference);
    }

    private Email mockEmail(String address, String name) throws Exception {
        Email email = mock(Email.class);
        when(email.getBody()).thenReturn("+1");
        when(email.getFrom()).thenReturn(new InternetAddress(address, name));
        return email;
    }

    private void prepareExecution(Mailer mailer, List<Email> thread) throws IOException, IllegalAccessException {
        CredentialsService credentialsService = mock(CredentialsService.class);
        when(credentialsService.getAsfCredentials()).thenReturn(new Credentials("johndoe", "secret"));

        MembersFinder membersFinder = spy(new MembersFinder());
        Set<Member> members = new HashSet<>(){{
            add(new Member("johndoe", "John Doe", true));
            add(new Member("alice", "Alice", true));
            add(new Member("bob", "Bob", true));
            add(new Member("charlie", "Charlie", true));
            add(new Member("daniel", "Daniel", false));
            add(new Member("joerghoh", "Joerg Hoh", true));
        }};
        FieldUtils.writeField(membersFinder, "members", members, true);
        FieldUtils.writeField(membersFinder, "lastCheck", System.currentTimeMillis(), true);

        StagingRepository stagingRepository = mock(StagingRepository.class);
        when(stagingRepository.getDescription()).thenReturn("Apache Sling CLI Test 1.0.0");
        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.find(123)).thenReturn(stagingRepository);
        Release release = Release.fromString("Apache Sling CLI Test 1.0.0").get(0);
        when(repositoryService.getReleases(stagingRepository)).thenReturn(Set.of(release));


        VoteThreadFinder voteThreadFinder = mock(VoteThreadFinder.class);
        when(voteThreadFinder.findVoteThread("CLI Test 1.0.0")).thenReturn(thread);

        osgiContext.registerService(CredentialsService.class, credentialsService);
        osgiContext.registerInjectActivateService(membersFinder);
        osgiContext.registerService(RepositoryService.class, repositoryService);
        osgiContext.registerService(VoteThreadFinder.class, voteThreadFinder);
        osgiContext.registerService(Mailer.class, mailer);
    }
}
