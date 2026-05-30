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

import java.util.Arrays;
import java.util.Collections;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.junit.LogCapture;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class ListCommandTest {

    @Rule
    public OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(ListCommand.class);

    @Test
    public void testList() throws Exception {
        StagingRepository repo1 = mock(StagingRepository.class);
        when(repo1.getRepositoryId()).thenReturn("orgapachesling-1");
        when(repo1.getUserId()).thenReturn("jagger");
        when(repo1.getDescription()).thenReturn("Apache Sling CLI Test 1.0.0");

        StagingRepository repo2 = mock(StagingRepository.class);
        when(repo2.getRepositoryId()).thenReturn("orgapachesling-2");
        when(repo2.getUserId()).thenReturn("richards");
        when(repo2.getDescription()).thenReturn("Apache Sling CLI Test 2.0.0");

        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.list()).thenReturn(Arrays.asList(repo1, repo2));
        osgiContext.registerService(repositoryService);

        Command list = createCommand();
        assertEquals(0, (int) list.call());

        assertTrue(logCapture.containsMessage("orgapachesling-1"));
        assertTrue(logCapture.containsMessage("orgapachesling-2"));
        // the staging user is shown alongside the id/state/description
        assertTrue(logCapture.containsMessage("jagger"));
        assertTrue(logCapture.containsMessage("richards"));
        assertTrue(logCapture.containsMessage("Apache Sling CLI Test 1.0.0"));
        assertTrue(logCapture.containsMessage("Apache Sling CLI Test 2.0.0"));
    }

    @Test
    public void testListCollapsesNewlines() throws Exception {
        StagingRepository repo = mock(StagingRepository.class);
        when(repo.getRepositoryId()).thenReturn("orgapachesling-1");
        when(repo.getDescription()).thenReturn("line1\nline2");

        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.list()).thenReturn(Collections.singletonList(repo));
        osgiContext.registerService(repositoryService);

        Command list = createCommand();
        assertEquals(0, (int) list.call());

        assertTrue(logCapture.containsMessage("line1 line2"));
    }

    private Command createCommand() {
        ListCommand listCommand = spy(new ListCommand());
        osgiContext.registerInjectActivateService(listCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the ListCommand from the mocked OSGi environment.",
                result instanceof ListCommand);
        return result;
    }
}
