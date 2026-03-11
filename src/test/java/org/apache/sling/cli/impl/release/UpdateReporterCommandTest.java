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
import java.util.HashSet;
import java.util.List;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.junit.LogCapture;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class UpdateReporterCommandTest {

    private CloseableHttpClient client;

    @Rule
    public OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(UpdateReporterCommand.class);

    @Before
    public void before() throws IOException {
        RepositoryService repositoryService = mock(RepositoryService.class);
        StagingRepository stagingRepository = mock(StagingRepository.class);
        when(stagingRepository.getDescription()).thenReturn("Apache Sling CLI 1, Apache Sling CLI 2");
        when(repositoryService.find(42)).thenReturn(stagingRepository);
        List<Release> releases = Release.fromString("Apache Sling CLI 1, Apache Sling CLI 2");
        when(repositoryService.getReleases(stagingRepository)).thenReturn(new HashSet<>(releases));

        HttpClientFactory httpClientFactory = mock(HttpClientFactory.class);
        client = mock(CloseableHttpClient.class);
        when(httpClientFactory.newClient()).thenReturn(client);

        osgiContext.registerService(repositoryService);
        osgiContext.registerService(httpClientFactory);
    }

    @Test
    public void testDryRun() throws Exception {
        Command updateReporter = createCommand(42, ExecutionMode.DRY_RUN);
        assertEquals(0, (int) updateReporter.call());
        verifyNoInteractions(client);
        assertEquals(3, logCapture.size());
        assertTrue(logCapture.containsMessage("The following releases would be added to the Apache Reporter System:"));
        assertTrue(logCapture.containsMessage("  - Apache Sling CLI 1"));
        assertTrue(logCapture.containsMessage("  - Apache Sling CLI 2"));
    }

    @Test
    public void testInteractive() throws Exception {
        try (MockedStatic<UserInput> userInputMock = mockStatic(UserInput.class)) {
            Command updateReporter = createCommand(42, ExecutionMode.INTERACTIVE);
            CloseableHttpResponse response = mock(CloseableHttpResponse.class);
            StatusLine statusLine = mock(StatusLine.class);
            String question =
                    "Should the following releases be added to the Apache Reporter System?\n  - Apache Sling CLI 1\n  - Apache Sling CLI 2\n";
            userInputMock.when(() -> UserInput.yesNo(question, InputOption.YES)).thenReturn(InputOption.YES);
            when(response.getStatusLine()).thenReturn(statusLine);
            when(statusLine.getStatusCode()).thenReturn(200);
            when(client.execute(any())).thenReturn(response);
            assertEquals(0, (int) updateReporter.call());
            verify(client, times(2)).execute(any());
        }
    }

    @Test
    public void testAuto() throws Exception {
        Command updateReporter = createCommand(42, ExecutionMode.AUTO);
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        when(response.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(200);
        when(client.execute(any())).thenReturn(response);
        assertEquals(0, (int) updateReporter.call());
        verify(client, times(2)).execute(any());
    }

    private Command createCommand(int repositoryId, ExecutionMode executionMode) throws IllegalAccessException {
        UpdateReporterCommand updateReporterCommand = spy(new UpdateReporterCommand());
        FieldUtils.writeField(updateReporterCommand, "repositoryId", repositoryId, true);
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        FieldUtils.writeField(reusableCLIOptions, "executionMode", executionMode, true);
        FieldUtils.writeField(updateReporterCommand, "reusableCLIOptions", reusableCLIOptions, true);
        osgiContext.registerInjectActivateService(updateReporterCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the UpdateReporterCommand from the mocked OSGi environment.",
                result instanceof UpdateReporterCommand);
        return result;
    }
}
