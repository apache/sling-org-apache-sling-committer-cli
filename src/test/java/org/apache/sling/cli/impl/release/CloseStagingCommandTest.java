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

import java.util.Set;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.junit.LogCapture;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.MockedStatic;
import picocli.CommandLine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CloseStagingCommandTest {

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(CloseStagingCommand.class);

    private RepositoryService repositoryService;
    private StagingRepository stagingRepository;

    @Before
    public void before() throws Exception {
        stagingRepository = mock(StagingRepository.class);
        when(stagingRepository.getRepositoryId()).thenReturn("orgapachesling-123");
        when(stagingRepository.getDescription()).thenReturn("Apache Sling CLI Test 1.0.0");

        repositoryService = mock(RepositoryService.class);
        when(repositoryService.findAny(123)).thenReturn(stagingRepository);
        Set<Release> releases =
                Set.of(Release.fromString("Apache Sling CLI Test 1.0.0").get(0));
        when(repositoryService.getReleasesFromContent(stagingRepository)).thenReturn(releases);

        osgiContext.registerService(RepositoryService.class, repositoryService);
    }

    @Test
    public void testDryRun() throws Exception {
        Command command = createCommand(123, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        assertTrue(logCapture.containsMessage(
                "Would close staging repository orgapachesling-123 with description \"Apache Sling CLI Test 1.0.0\"."));
        verify(repositoryService, never()).close(any(), any());
    }

    @Test
    public void testInteractiveYes() throws Exception {
        try (MockedStatic<UserInput> userInputMock = mockStatic(UserInput.class)) {
            String question =
                    "Close staging repository orgapachesling-123 with description \"Apache Sling CLI Test 1.0.0\"?";
            userInputMock.when(() -> UserInput.yesNo(question, InputOption.YES)).thenReturn(InputOption.YES);
            Command command = createCommand(123, ExecutionMode.INTERACTIVE);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());
            verify(repositoryService).close(stagingRepository, "Apache Sling CLI Test 1.0.0");
        }
    }

    @Test
    public void testAuto() throws Exception {
        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        verify(repositoryService, times(1)).close(stagingRepository, "Apache Sling CLI Test 1.0.0");
    }

    @Test
    public void testInteractiveNoAborts() throws Exception {
        try (MockedStatic<UserInput> userInputMock = mockStatic(UserInput.class)) {
            userInputMock.when(() -> UserInput.yesNo(any(), any())).thenReturn(InputOption.NO);
            Command command = createCommand(123, ExecutionMode.INTERACTIVE);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());
            assertTrue(logCapture.containsMessage("Aborted."));
            verify(repositoryService, never()).close(any(), any());
        }
    }

    @Test
    public void testDescriptionFallsBackToRepositoryDescription() throws Exception {
        // no releases can be derived from the (still open) repository content: fall back to the
        // repository's own description
        when(repositoryService.getReleasesFromContent(stagingRepository)).thenReturn(Set.of());
        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        verify(repositoryService).close(stagingRepository, "Apache Sling CLI Test 1.0.0");
    }

    @Test
    public void testIOExceptionReturnsSoftware() throws Exception {
        when(repositoryService.findAny(123)).thenThrow(new java.io.IOException("nexus down"));
        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.SOFTWARE, (int) command.call());
        assertTrue(logCapture.containsMessage("Failed executing command"));
    }

    private Command createCommand(int repositoryId, ExecutionMode executionMode) throws IllegalAccessException {
        CloseStagingCommand closeStagingCommand = spy(new CloseStagingCommand());
        FieldUtils.writeField(closeStagingCommand, "repositoryId", repositoryId, true);
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        FieldUtils.writeField(reusableCLIOptions, "executionMode", executionMode, true);
        FieldUtils.writeField(closeStagingCommand, "reusableCLIOptions", reusableCLIOptions, true);
        osgiContext.registerInjectActivateService(closeStagingCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the CloseStagingCommand from the mocked OSGi environment.",
                result instanceof CloseStagingCommand);
        return result;
    }
}
