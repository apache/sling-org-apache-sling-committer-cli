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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DropCommandTest {

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(DropCommand.class);

    private RepositoryService repositoryService;
    private StagingRepository stagingRepository;

    @Before
    public void before() throws Exception {
        stagingRepository = mock(StagingRepository.class);
        when(stagingRepository.getRepositoryId()).thenReturn("orgapachesling-123");
        when(stagingRepository.getDescription()).thenReturn("Apache Sling CLI Test 1.0.0");

        repositoryService = mock(RepositoryService.class);
        when(repositoryService.findAny(123)).thenReturn(stagingRepository);

        osgiContext.registerService(RepositoryService.class, repositoryService);
    }

    @Test
    public void testDryRun() throws Exception {
        Command command = createCommand(123, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        verify(repositoryService, never()).drop(any());
        assertTrue(logCapture.containsMessage("Would drop staging repository"));
    }

    @Test
    public void testInteractiveYes() throws Exception {
        try (MockedStatic<UserInput> userInputMock = mockStatic(UserInput.class)) {
            userInputMock.when(() -> UserInput.yesNo(anyString(), any())).thenReturn(InputOption.YES);
            Command command = createCommand(123, ExecutionMode.INTERACTIVE);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());
            verify(repositoryService, times(1)).drop(stagingRepository);
        }
    }

    @Test
    public void testInteractiveNo() throws Exception {
        try (MockedStatic<UserInput> userInputMock = mockStatic(UserInput.class)) {
            userInputMock.when(() -> UserInput.yesNo(anyString(), any())).thenReturn(InputOption.NO);
            Command command = createCommand(123, ExecutionMode.INTERACTIVE);
            assertEquals(CommandLine.ExitCode.OK, (int) command.call());
            verify(repositoryService, never()).drop(any());
            assertTrue(logCapture.containsMessage("Aborted."));
        }
    }

    @Test
    public void testAuto() throws Exception {
        Command command = createCommand(123, ExecutionMode.AUTO);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        verify(repositoryService, times(1)).drop(stagingRepository);
    }

    private Command createCommand(int repositoryId, ExecutionMode executionMode) throws IllegalAccessException {
        DropCommand dropCommand = spy(new DropCommand());
        FieldUtils.writeField(dropCommand, "repositoryId", repositoryId, true);
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        FieldUtils.writeField(reusableCLIOptions, "executionMode", executionMode, true);
        FieldUtils.writeField(dropCommand, "reusableCLIOptions", reusableCLIOptions, true);
        osgiContext.registerInjectActivateService(dropCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the DropCommand from the mocked OSGi environment.",
                result instanceof DropCommand);
        return result;
    }
}
