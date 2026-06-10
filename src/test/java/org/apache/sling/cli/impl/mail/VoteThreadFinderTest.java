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
package org.apache.sling.cli.impl.mail;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class VoteThreadFinderTest {

    @Test
    public void testFindVoteThreadIdsIgnoresUnrelatedSearchHits() {
        JsonObject stats = parseStatsJson();

        assertEquals(
                List.of("vote-root", "vote-1", "vote-2"),
                VoteThreadFinder.findVoteThreadIds(stats, "[VOTE] Release Apache Sling MCP Server 0.1.4"));
    }

    @Test
    public void testFindVoteThreadBuildsEmailsFromThreadStructOrder() throws IOException {
        VoteThreadFinder finder = new VoteThreadFinder() {
            @Override
            JsonObject loadVoteThreadStats(String threadSubject) {
                return parseStatsJson();
            }

            @Override
            Email createEmail(String id) {
                Email email = mock(Email.class);
                when(email.getId()).thenReturn(id);
                return email;
            }
        };

        assertEquals(
                List.of("vote-root", "vote-1", "vote-2"),
                finder.findVoteThread("Apache Sling MCP Server 0.1.4").stream()
                        .map(Email::getId)
                        .collect(Collectors.toList()));
    }

    private static JsonObject parseStatsJson() {
        return new JsonParser()
                .parse("{"
                        + "\"thread_struct\":["
                        + "{\"tid\":\"jira-id\",\"subject\":\"[jira] [Updated] (SLING-13149) Main feature should not be in src/main\",\"children\":[]},"
                        + "{\"tid\":\"vote-root\",\"subject\":\"[VOTE] Release Apache Sling MCP Server 0.1.4\",\"children\":["
                        + "{\"tid\":\"vote-1\",\"subject\":\"Re: [VOTE] Release Apache Sling MCP Server 0.1.4\",\"children\":[]},"
                        + "{\"tid\":\"vote-2\",\"subject\":\"RE: [VOTE] Release Apache Sling MCP Server 0.1.4\",\"children\":[]}"
                        + "]}"
                        + "]"
                        + "}")
                .getAsJsonObject();
    }
}
