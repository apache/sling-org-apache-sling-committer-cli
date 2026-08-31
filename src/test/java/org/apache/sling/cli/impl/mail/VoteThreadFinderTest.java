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
import java.net.URI;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class VoteThreadFinderTest {

    private static final String STATS_RESPONSE = "{\"emails\":["
            + "{\"id\":\"vote-root\",\"message-id\":\"<vote@sling.apache.org>\"},"
            + "{\"id\":\"vote-1\",\"message-id\":\"<reply-1@sling.apache.org>\"},"
            + "{\"id\":\"vote-2\"}"
            + "]}";

    @Test
    public void testMessageIdsAreReadFromTheSearchResponse() throws IOException {
        List<Email> thread = finder(STATS_RESPONSE).findVoteThread("Apache Sling CLI Test 1.0.0");

        assertEquals(
                List.of("vote-root", "vote-1", "vote-2"),
                thread.stream().map(Email::getId).toList());
        assertEquals("<vote@sling.apache.org>", thread.get(0).getMessageId());
        assertEquals("<reply-1@sling.apache.org>", thread.get(1).getMessageId());
        // The archive does not always report a Message-ID.
        assertNull(thread.get(2).getMessageId());
    }

    @Test
    public void testEmptySearchResponse() throws IOException {
        assertEquals(List.of(), finder("{\"emails\":[]}").findVoteThread("Apache Sling CLI Test 1.0.0"));
    }

    @Test(expected = IllegalStateException.class)
    public void testSearchResponseWithoutEmails() throws IOException {
        finder("{}").findVoteThread("Apache Sling CLI Test 1.0.0");
    }

    private static VoteThreadFinder finder(String statsResponse) {
        return new VoteThreadFinder() {

            @Override
            JsonObject fetchThreadStats(URI uri) {
                return new JsonParser().parse(statsResponse).getAsJsonObject();
            }

            @Override
            Email createEmail(String id, String messageId) {
                // The real constructor retrieves the message source over HTTP.
                Email email = mock(Email.class);
                when(email.getId()).thenReturn(id);
                when(email.getMessageId()).thenReturn(messageId);
                return email;
            }
        };
    }
}
