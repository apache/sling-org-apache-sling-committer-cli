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

import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.ExecutionContext;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.nexus.StagingRepositoryFinder;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@RunWith(PowerMockRunner.class)
@PowerMockIgnore({
    // https://github.com/powermock/powermock/issues/864
    "com.sun.org.apache.xerces.*",
    "javax.xml.*",
    "org.w3c.dom.*"
})
public class UpdateReporterCommandTest {

    private CloseableHttpClient client;

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Before
    public void before() throws IOException {
        StagingRepositoryFinder repositoryFinder = mock(StagingRepositoryFinder.class);
        StagingRepository stagingRepository = mock(StagingRepository.class);
        when(stagingRepository.getDescription()).thenReturn("Apache Sling CLI 1, Apache Sling CLI 2");
        when(repositoryFinder.find(42)).thenReturn(stagingRepository);

        HttpClientFactory httpClientFactory = mock(HttpClientFactory.class);
        client = mock(CloseableHttpClient.class);
        when(httpClientFactory.newClient()).thenReturn(client);

        osgiContext.registerService(repositoryFinder);
        osgiContext.registerService(httpClientFactory);
    }

    @Test
    @PrepareForTest({LoggerFactory.class})
    public void testDryRun() throws Exception {
        mockStatic(LoggerFactory.class);
        Logger logger = mock(Logger.class);
        when(LoggerFactory.getLogger(UpdateReporterCommand.class)).thenReturn(logger);

        osgiContext.registerInjectActivateService(new UpdateReporterCommand());
        Command updateReporter = osgiContext.getService(Command.class);
        assertTrue("Expected to retrieve the UpdateReporterCommand from the mocked OSGi environment.",
                updateReporter instanceof UpdateReporterCommand);
        updateReporter.execute(new ExecutionContext(ExecutionContext.Mode.DRY_RUN, "42"));
        verify(logger).info("The following {} would be added to the Apache Reporter System:", "releases");
        verify(logger).info("  - {}", "Apache Sling CLI 1");
        verify(logger).info("  - {}", "Apache Sling CLI 2");
        verifyNoMoreInteractions(logger);
    }

    @Test
    @PrepareForTest({UserInput.class})
    public void testInteractive() throws Exception {
        osgiContext.registerInjectActivateService(new UpdateReporterCommand());
        Command updateReporter = osgiContext.getService(Command.class);
        assertTrue("Expected to retrieve the UpdateReporterCommand from the mocked OSGi environment.",
                updateReporter instanceof UpdateReporterCommand);
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        mockStatic(UserInput.class);
        String question =
                "Should the following releases be added to the Apache Reporter System?\n  - Apache Sling CLI 1\n  - Apache Sling CLI 2\n";
        when(UserInput.yesNo(question, InputOption.YES)).thenReturn(InputOption.YES);
        when(response.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(200);
        when(client.execute(any())).thenReturn(response);
        updateReporter.execute(new ExecutionContext(ExecutionContext.Mode.INTERACTIVE, "42"));
        verify(client, times(2)).execute(any());
    }

    @Test
    public void testAuto() throws Exception {
        osgiContext.registerInjectActivateService(new UpdateReporterCommand());
        Command updateReporter = osgiContext.getService(Command.class);
        assertTrue("Expected to retrieve the UpdateReporterCommand from the mocked OSGi environment.",
                updateReporter instanceof UpdateReporterCommand);
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        when(response.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(200);
        when(client.execute(any())).thenReturn(response);
        updateReporter.execute(new ExecutionContext(ExecutionContext.Mode.AUTO, "42"));
        verify(client, times(2)).execute(any());
    }


}
