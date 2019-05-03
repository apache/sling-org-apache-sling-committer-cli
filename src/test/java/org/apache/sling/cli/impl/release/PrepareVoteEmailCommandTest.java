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

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.ExecutionContext;
import org.apache.sling.cli.impl.jira.Version;
import org.apache.sling.cli.impl.jira.VersionClient;
import org.apache.sling.cli.impl.mail.Mailer;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.nexus.StagingRepositoryFinder;
import org.apache.sling.cli.impl.people.Member;
import org.apache.sling.cli.impl.people.MembersFinder;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.ServiceReference;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(PowerMockRunner.class)
@PrepareForTest({PrepareVoteEmailCommand.class})
@PowerMockIgnore({
                         // https://github.com/powermock/powermock/issues/864
                         "com.sun.org.apache.xerces.*",
                         "javax.xml.*",
                         "org.w3c.dom.*"
                 })
public class PrepareVoteEmailCommandTest {

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Test
    public void testPrepareEmailGeneration() throws Exception {
        MembersFinder membersFinder = mock(MembersFinder.class);
        when(membersFinder.getCurrentMember()).thenReturn(new Member("johndoe", "John Doe", true));

        StagingRepository stagingRepository = mock(StagingRepository.class);
        when(stagingRepository.getDescription()).thenReturn("Apache Sling CLI Test 1.0.0");
        StagingRepositoryFinder stagingRepositoryFinder = mock(StagingRepositoryFinder.class);
        when(stagingRepositoryFinder.find(123)).thenReturn(stagingRepository);

        VersionClient versionFinder = mock(VersionClient.class);
        Version version = mock(Version.class);
        when(version.getName()).thenReturn("CLI Test 1.0.0");
        when(version.getId()).thenReturn(1);
        when(version.getIssuesFixedCount()).thenReturn(42);
        when(versionFinder.find(Release.fromString("CLI Test 1.0.0").get(0))).thenReturn(version);

        osgiContext.registerService(MembersFinder.class, membersFinder);
        osgiContext.registerService(StagingRepositoryFinder.class, stagingRepositoryFinder);
        osgiContext.registerService(VersionClient.class, versionFinder);

        Mailer mailer = mock(Mailer.class);
        prepareExecution(mailer);
        ExecutionContext context = new ExecutionContext("123", "--auto");
        osgiContext.registerInjectActivateService(new PrepareVoteEmailCommand());

        ServiceReference<?> reference =
                osgiContext.bundleContext().getServiceReference(Command.class.getName());
        Command command = (Command) osgiContext.bundleContext().getService(reference);
        command.execute(context);
        verify(mailer).send(
                "From: John Doe <johndoe@apache.org>\n" +
                        "To: \"Sling Developers List\" <dev@sling.apache.org>\n" +
                        "Subject: [VOTE] Release Apache Sling CLI Test 1.0.0\n" +
                        "\n" +
                        "Hi,\n" +
                        "\n" +
                        "We solved 42 issue(s) in this release:\n" +
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
                        "John Doe\n" +
                        "\n");
    }

    private void prepareExecution(Mailer mailer) throws IOException {
        MembersFinder membersFinder = mock(MembersFinder.class);
        when(membersFinder.getCurrentMember()).thenReturn(new Member("johndoe", "John Doe", true));

        StagingRepository stagingRepository = mock(StagingRepository.class);
        when(stagingRepository.getDescription()).thenReturn("Apache Sling CLI Test 1.0.0");
        StagingRepositoryFinder stagingRepositoryFinder = mock(StagingRepositoryFinder.class);
        when(stagingRepositoryFinder.find(123)).thenReturn(stagingRepository);

        VersionClient versionClient = mock(VersionClient.class);
        Version version = mock(Version.class);
        when(version.getName()).thenReturn("CLI Test 1.0.0");
        when(version.getId()).thenReturn(1);
        when(version.getIssuesFixedCount()).thenReturn(42);
        when(versionClient.find(Release.fromString("CLI Test 1.0.0").get(0))).thenReturn(version);

        osgiContext.registerService(MembersFinder.class, membersFinder);
        osgiContext.registerService(StagingRepositoryFinder.class, stagingRepositoryFinder);
        osgiContext.registerService(VersionClient.class, versionClient);
        osgiContext.registerService(Mailer.class, mailer);
    }
}
