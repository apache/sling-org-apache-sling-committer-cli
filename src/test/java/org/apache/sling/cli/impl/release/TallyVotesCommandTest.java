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

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.DateProvider;
import org.apache.sling.cli.impl.ExecutionMode;
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
import org.junit.runner.RunWith;
import org.osgi.framework.ServiceReference;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@RunWith(PowerMockRunner.class)
@PowerMockIgnore({
                         // https://github.com/powermock/powermock/issues/864
                         "com.sun.org.apache.xerces.*",
                         "javax.xml.*",
                         "org.w3c.dom.*"
                 })
public class TallyVotesCommandTest {

    @Before
    public void beforeClass() {
        DateProvider dateProvider = mock(DateProvider.class);
        when(dateProvider.getCurrentDateForEmailHeader()).thenReturn("Thu, 1 Jan 1970 01:00:00 +0100");
        osgiContext.registerService(DateProvider.class, dateProvider);
    }

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Test
    @PrepareForTest({LoggerFactory.class})
    public void testDryRun() throws Exception {
        mockStatic(LoggerFactory.class);
        Logger logger = mock(Logger.class);
        when(LoggerFactory.getLogger(TallyVotesCommand.class)).thenReturn(logger);
        List<Email> thread = new ArrayList<>(){{
            add(mockEmail("johndoe@apache.org", "John Doe"));
            add(mockEmail("alice@apache.org", "Alice"));
            add(mockEmail("bob@apache.org", "Bob"));
            add(mockEmail("charlie@apache.org", "Charlie"));
            add(mockEmail("daniel@apache.org", "Daniel"));
            add(mockEmail("johndoe@apache.org", "John Doe"));
        }};
        prepareExecution(mock(Mailer.class), thread);
        TallyVotesCommand tallyVotesCommand = spy(new TallyVotesCommand());
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        Whitebox.setInternalState(reusableCLIOptions, "executionMode", ExecutionMode.DRY_RUN);
        Whitebox.setInternalState(tallyVotesCommand, "repositoryId", 123);
        Whitebox.setInternalState(tallyVotesCommand, "reusableCLIOptions", reusableCLIOptions);
        osgiContext.registerInjectActivateService(tallyVotesCommand);
        ServiceReference<?> reference =
                osgiContext.bundleContext().getServiceReference(Command.class.getName());
        Command command = (Command) osgiContext.bundleContext().getService(reference);
        assertEquals(CommandLine.ExitCode.OK, (int)command.call());
        verify(logger).info(
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

    @Test
    @PrepareForTest({LoggerFactory.class})
    public void testDryRunNotEnoughBindingVotes() throws Exception {
        mockStatic(LoggerFactory.class);
        Logger logger = mock(Logger.class);
        when(LoggerFactory.getLogger(TallyVotesCommand.class)).thenReturn(logger);
        List<Email> thread = new ArrayList<>(){{
            add(mockEmail("johndoe@apache.org", "John Doe"));
            add(mockEmail("alice@apache.org", "Alice"));
            add(mockEmail("bob@apache.org", "Bob"));
            add(mockEmail("daniel@apache.org", "Daniel"));
        }};
        prepareExecution(mock(Mailer.class), thread);
        TallyVotesCommand tallyVotesCommand = spy(new TallyVotesCommand());
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        Whitebox.setInternalState(reusableCLIOptions, "executionMode", ExecutionMode.DRY_RUN);
        Whitebox.setInternalState(tallyVotesCommand, "repositoryId", 123);
        Whitebox.setInternalState(tallyVotesCommand, "reusableCLIOptions", reusableCLIOptions);
        osgiContext.registerInjectActivateService(tallyVotesCommand);
        ServiceReference<?> reference =
                osgiContext.bundleContext().getServiceReference(Command.class.getName());
        Command command = (Command) osgiContext.bundleContext().getService(reference);
        assertEquals(CommandLine.ExitCode.USAGE, (int)command.call());
        verify(logger).info(
                "Release {} does not have at least 3 binding votes.",
                "Apache Sling CLI Test 1.0.0"
        );

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
        TallyVotesCommand tallyVotesCommand = spy(new TallyVotesCommand());
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        Whitebox.setInternalState(reusableCLIOptions, "executionMode", ExecutionMode.AUTO);
        Whitebox.setInternalState(tallyVotesCommand, "repositoryId", 123);
        Whitebox.setInternalState(tallyVotesCommand, "reusableCLIOptions", reusableCLIOptions);
        osgiContext.registerInjectActivateService(tallyVotesCommand);
        ServiceReference<?> reference =
                osgiContext.bundleContext().getServiceReference(Command.class.getName());
        Command command = (Command) osgiContext.bundleContext().getService(reference);
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

    private Email mockEmail(String address, String name) throws Exception {
        Email email = mock(Email.class);
        when(email.getBody()).thenReturn("+1");
        when(email.getFrom()).thenReturn(new InternetAddress(address, name));
        return email;
    }

    private void prepareExecution(Mailer mailer, List<Email> thread) throws IOException {
        CredentialsService credentialsService = mock(CredentialsService.class);
        when(credentialsService.getAsfCredentials()).thenReturn(new Credentials("johndoe", "secret"));

        MembersFinder membersFinder = spy(new MembersFinder());
        Set<Member> members = new HashSet<>(){{
            add(new Member("johndoe", "John Doe", true));
            add(new Member("alice", "Alice", true));
            add(new Member("bob", "Bob", true));
            add(new Member("charlie", "Charlie", true));
            add(new Member("daniel", "Daniel", false));
        }};
        Whitebox.setInternalState(membersFinder, "members", members);
        Whitebox.setInternalState(membersFinder, "lastCheck", System.currentTimeMillis());

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
