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
import static org.mockito.ArgumentMatchers.anyString;
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
        verify(versionClient, never()).release(any());
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
            verify(versionClient, times(1)).release(any());
        }
    }

    @Test
    public void testAuto() throws Exception {
        prepare();
        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        verify(versionClient, times(1)).release(any());
    }

    private Command createCommand(int repositoryId, ExecutionMode executionMode) throws Exception {
        ReleaseJiraVersionCommand releaseJiraVersionCommand = spy(new ReleaseJiraVersionCommand());
        FieldUtils.writeField(releaseJiraVersionCommand, "repositoryId", repositoryId, true);
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
