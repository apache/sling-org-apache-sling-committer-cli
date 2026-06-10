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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.osgi.service.component.annotations.Component;

@Component(service = VoteThreadFinder.class)
public class VoteThreadFinder {

    private static final Pattern REPLY_PREFIX_PATTERN = Pattern.compile("(?i)^(re|fw|fwd):\\s*");

    public List<Email> findVoteThread(String releaseName) throws IOException {
        String threadSubject = "[VOTE] Release " + releaseName;
        JsonObject stats = loadVoteThreadStats(threadSubject);
        List<Email> emails = new ArrayList<>();
        for (String threadId : findVoteThreadIds(stats, threadSubject)) {
            emails.add(createEmail(threadId));
        }
        return emails;
    }

    JsonObject loadVoteThreadStats(String threadSubject) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            URI uri = new URIBuilder("https://lists.apache.org/api/stats.lua")
                    .addParameter("domain", "sling.apache.org")
                    .addParameter("list", "dev")
                    .addParameter("d", "lte=1M")
                    .addParameter("q", threadSubject)
                    .build();

            HttpGet get = new HttpGet(uri);
            try (CloseableHttpResponse response = client.execute(get)) {
                try (InputStream content = response.getEntity().getContent();
                        InputStreamReader reader = new InputStreamReader(content)) {
                    if (response.getStatusLine().getStatusCode() != 200) {
                        throw new IOException("Status line : " + response.getStatusLine());
                    }
                    JsonParser parser = new JsonParser();
                    JsonObject stats = parser.parse(reader).getAsJsonObject();
                    JsonElement threadStructJson = stats.get("thread_struct");
                    if (threadStructJson == null) {
                        throw new IllegalStateException(String.format(
                                "Unable to correctly parse JSON from %s. Missing \"thread_struct\" "
                                        + "property in the JSON response.",
                                uri.toString()));
                    }
                    return stats;
                }
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    Email createEmail(String id) {
        return new Email(id);
    }

    static List<String> findVoteThreadIds(JsonObject stats, String threadSubject) {
        List<String> threadIds = new ArrayList<>();
        JsonElement threadStructJson = stats.get("thread_struct");
        if (threadStructJson == null || !threadStructJson.isJsonArray()) {
            return threadIds;
        }

        JsonArray threads = threadStructJson.getAsJsonArray();
        for (JsonElement thread : threads) {
            JsonObject threadObject = thread.getAsJsonObject();
            if (isVoteThreadRoot(threadObject, threadSubject)) {
                collectThreadIds(threadObject, threadIds);
                break;
            }
        }
        return threadIds;
    }

    private static boolean isVoteThreadRoot(JsonObject threadObject, String threadSubject) {
        JsonElement subject = threadObject.get("subject");
        return subject != null && normalizeSubject(subject.getAsString()).equals(normalizeSubject(threadSubject));
    }

    private static void collectThreadIds(JsonObject threadObject, List<String> threadIds) {
        JsonElement threadId = threadObject.get("tid");
        if (threadId != null) {
            threadIds.add(threadId.getAsString());
        }

        JsonElement children = threadObject.get("children");
        if (children != null && children.isJsonArray()) {
            for (JsonElement child : children.getAsJsonArray()) {
                collectThreadIds(child.getAsJsonObject(), threadIds);
            }
        }
    }

    private static String normalizeSubject(String subject) {
        String normalizedSubject = subject;
        while (true) {
            String candidate = REPLY_PREFIX_PATTERN.matcher(normalizedSubject).replaceFirst("");
            if (candidate.equals(normalizedSubject)) {
                return normalizedSubject;
            }
            normalizedSubject = candidate;
        }
    }
}
