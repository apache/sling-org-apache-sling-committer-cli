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

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.jira.Issue;
import org.apache.sling.cli.impl.jira.Version;
import org.apache.sling.cli.impl.jira.VersionClient;
import org.apache.sling.cli.impl.junit.LogCapture;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Rule;
import org.junit.Test;
import picocli.CommandLine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CreateJiraVersionCommandTest {

    private static final String VERSION_NAME = "Apache Sling CLI Test 1.0.0";

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(CreateJiraVersionCommand.class);

    private VersionClient versionClient;

    private void prepare() {
        versionClient = mock(VersionClient.class);
        osgiContext.registerService(VersionClient.class, versionClient);
        osgiContext.registerService(RepositoryService.class, mock(RepositoryService.class));
    }

    @Test
    public void testDryRunCreatesNothing() throws Exception {
        prepare();
        Version version = mock(Version.class);
        when(version.getName()).thenReturn("1.0.0");
        when(versionClient.find(any())).thenReturn(version);
        when(versionClient.findSuccessorVersion(any())).thenReturn(null);
        when(versionClient.findUnresolvedIssues(any())).thenReturn(List.of());

        Command command = createCommand(ExecutionMode.DRY_RUN, VERSION_NAME);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        verify(versionClient, never()).create(anyString());
        assertTrue(logCapture.containsMessage("would be created"));
    }

    @Test
    public void testAutoCreatesSuccessor() throws Exception {
        prepare();
        Version version = mock(Version.class);
        when(version.getName()).thenReturn("1.0.0");
        Version successor = mock(Version.class);
        when(successor.getName()).thenReturn("1.0.2");
        when(versionClient.find(any())).thenReturn(version);
        when(versionClient.findSuccessorVersion(any())).thenReturn(null, successor);
        when(versionClient.findUnresolvedIssues(any())).thenReturn(List.of());

        Command command = createCommand(ExecutionMode.AUTO, VERSION_NAME);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        verify(versionClient, times(1)).create(anyString());
    }

    @Test
    public void testAutoMovesUnresolvedIssues() throws Exception {
        prepare();
        Version version = mock(Version.class);
        when(version.getName()).thenReturn("1.0.0");
        Version successor = mock(Version.class);
        when(successor.getName()).thenReturn("1.0.2");
        Issue issue = mock(Issue.class);
        when(issue.getKey()).thenReturn("SLING-123");
        when(issue.getSummary()).thenReturn("Some bug");
        when(versionClient.find(any())).thenReturn(version);
        when(versionClient.findSuccessorVersion(any())).thenReturn(successor);
        when(versionClient.findUnresolvedIssues(any())).thenReturn(List.of(issue));

        Command command = createCommand(ExecutionMode.AUTO, VERSION_NAME);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());

        verify(versionClient, never()).create(anyString());
        verify(versionClient, times(1)).moveIssuesToNewVersion(any(), any(), any());
    }

    private Command createCommand(ExecutionMode executionMode, String jiraVersionName) throws IllegalAccessException {
        CreateJiraVersionCommand createJiraVersionCommand = spy(new CreateJiraVersionCommand());
        FieldUtils.writeField(createJiraVersionCommand, "repositoryId", 123, true);
        FieldUtils.writeField(createJiraVersionCommand, "jiraVersionName", jiraVersionName, true);
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        FieldUtils.writeField(reusableCLIOptions, "executionMode", executionMode, true);
        FieldUtils.writeField(createJiraVersionCommand, "reusableCLIOptions", reusableCLIOptions, true);
        osgiContext.registerInjectActivateService(createJiraVersionCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the CreateJiraVersionCommand from the mocked OSGi environment.",
                result instanceof CreateJiraVersionCommand);
        return result;
    }
}
