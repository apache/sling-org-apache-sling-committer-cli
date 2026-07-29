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
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.jira.Issue;
import org.apache.sling.cli.impl.jira.VersionClient;
import org.apache.sling.cli.impl.junit.LogCapture;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.MockedStatic;
import picocli.CommandLine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReleaseJiraVersionCommandTest {

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(ReleaseJiraVersionCommand.class);

    private RepositoryService repositoryService;
    private VersionClient versionClient;

    private void prepare() throws Exception {
        StagingRepository stagingRepository = mock(StagingRepository.class);
        repositoryService = mock(RepositoryService.class);
        when(repositoryService.find(123)).thenReturn(stagingRepository);
        Set<Release> releases =
                Set.of(Release.fromString("Apache Sling CLI Test 1.0.0").get(0));
        when(repositoryService.getReleases(stagingRepository)).thenReturn(releases);

        Issue issue = mock(Issue.class);
        when(issue.getKey()).thenReturn("SLING-1");
        when(issue.getSummary()).thenReturn("A fixed issue");
        when(issue.getStatus()).thenReturn("Closed");
        when(issue.getResolution()).thenReturn("Fixed");

        versionClient = mock(VersionClient.class);
        when(versionClient.findFixedIssues(any())).thenReturn(List.of(issue));

        osgiContext.registerService(RepositoryService.class, repositoryService);
        osgiContext.registerService(VersionClient.class, versionClient);
    }

    @Test
    public void testDryRun() throws Exception {
        prepare();
        Command command = createCommand(123, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        verify(versionClient, never()).release(any(), any());
        assertTrue(logCapture.containsMessage("The following Jira versions would be released:"));
    }

    @Test
    public void testInteractiveYes() throws Exception {
        prepare();
        try (MockedStatic<UserInput> userInputMock = mockStatic(UserInput.class)) {
            userInputMock
                    .when(() -> UserInput.yesNo(anyString(), any(InputOption.class)))
                    .thenReturn(InputOption.YES);
            Command command = createCommand(123, ExecutionMode.INTERACTIVE);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());
            verify(versionClient, times(1)).release(any(), any());
        }
    }

    @Test
    public void testAuto() throws Exception {
        prepare();
        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        verify(versionClient, times(1)).release(any(), any());
    }

    @Test
    public void testReleaseByName() throws Exception {
        // the release is resolved from --release, so the command works without the (now-gone) staging repo
        prepare();
        Command command = createCommandByName("Apache Sling CLI Test 1.0.0", ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        verify(versionClient, times(1)).release(any(), any());
        verify(repositoryService, never()).find(anyInt());
    }

    @Test
    public void testGuardWarnsAndPassesStagingTimestamp() throws Exception {
        prepare();
        when(repositoryService.find(123).getCreated()).thenReturn(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        Issue late = mock(Issue.class);
        when(late.getKey()).thenReturn("SLING-13260");
        when(late.getSummary()).thenReturn("Late tagged issue");
        when(versionClient.findIssuesFixVersionedAfter(any(), any())).thenReturn(List.of(late));

        Command command = createCommand(123, ExecutionMode.AUTO, false);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        assertTrue(logCapture.containsMessage("Refusing to release JIRA version Apache Sling CLI Test 1.0.0"));
        assertTrue(logCapture.containsMessage("SLING-13260"));
        // the staging timestamp is handed to release(), which is the authoritative guard
        verify(versionClient).release(any(), eq(java.time.Instant.parse("2020-01-01T00:00:00Z")));
    }

    @Test
    public void testForcePassesNullTimestamp() throws Exception {
        prepare();
        when(repositoryService.find(123).getCreated()).thenReturn(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        Issue late = mock(Issue.class);
        when(late.getKey()).thenReturn("SLING-13260");
        when(late.getSummary()).thenReturn("Late tagged issue");
        when(versionClient.findIssuesFixVersionedAfter(any(), any())).thenReturn(List.of(late));

        Command command = createCommand(123, ExecutionMode.AUTO, true);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        assertTrue(logCapture.containsMessage("closing them anyway because --force-close-late-issues was given"));
        verify(versionClient).release(any(), isNull());
    }

    private Command createCommand(int repositoryId, ExecutionMode executionMode) throws Exception {
        return createCommand(repositoryId, executionMode, false);
    }

    private Command createCommand(int repositoryId, ExecutionMode executionMode, boolean forceCloseLateIssues)
            throws Exception {
        ReleaseJiraVersionCommand releaseJiraVersionCommand = spy(new ReleaseJiraVersionCommand());
        FieldUtils.writeField(releaseJiraVersionCommand, "repositoryId", repositoryId, true);
        FieldUtils.writeField(releaseJiraVersionCommand, "forceCloseLateIssues", forceCloseLateIssues, true);
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        FieldUtils.writeField(reusableCLIOptions, "executionMode", executionMode, true);
        FieldUtils.writeField(releaseJiraVersionCommand, "reusableCLIOptions", reusableCLIOptions, true);
        osgiContext.registerInjectActivateService(releaseJiraVersionCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the ReleaseJiraVersionCommand from the mocked OSGi environment.",
                result instanceof ReleaseJiraVersionCommand);
        return result;
    }

    private Command createCommandByName(String releaseName, ExecutionMode executionMode) throws Exception {
        ReleaseJiraVersionCommand releaseJiraVersionCommand = spy(new ReleaseJiraVersionCommand());
        FieldUtils.writeField(releaseJiraVersionCommand, "releaseName", releaseName, true);
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        FieldUtils.writeField(reusableCLIOptions, "executionMode", executionMode, true);
        FieldUtils.writeField(releaseJiraVersionCommand, "reusableCLIOptions", reusableCLIOptions, true);
        osgiContext.registerInjectActivateService(releaseJiraVersionCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the ReleaseJiraVersionCommand from the mocked OSGi environment.",
                result instanceof ReleaseJiraVersionCommand);
        return result;
    }
}
