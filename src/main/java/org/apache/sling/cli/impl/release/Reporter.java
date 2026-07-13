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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared access to the Apache Reporter System ({@code reporter.apache.org}) for recording releases.
 *
 * <p>Centralises the {@code addrelease.py} call so {@link UpdateReporterCommand} and
 * {@link FinalizeCommand} do not duplicate it. Note that {@code addrelease.py} returns HTTP 200 even on
 * failure, with the reason in the response body, so the body must be inspected to tell success from
 * failure.</p>
 */
final class Reporter {

    /** Outcome of an {@link #addRelease} call that did not raise a hard error. */
    enum Result {
        /** The release was recorded. */
        ADDED,
        /** The reporter rejected the write because the current user lacks committee access. */
        ACCESS_DENIED
    }

    static final String COMMITTEE = "sling";
    static final String OVERVIEW_URL = "https://reporter.apache.org/api/overview?" + COMMITTEE;
    static final String ADD_RELEASE_URL = "https://reporter.apache.org/addrelease.py";

    private static final Logger LOGGER = LoggerFactory.getLogger(Reporter.class);

    private Reporter() {}

    /**
     * Returns the set of release names the reporter already records for the Sling committee, so callers
     * can skip re-adding them. Returns {@code null} if the reporter could not be queried, in which case
     * callers should proceed without deduplication rather than risk skipping a real release.
     */
    static Set<String> fetchRegisteredReleaseNames(CloseableHttpClient client) {
        HttpGet get = new HttpGet(OVERVIEW_URL);
        get.setHeader("Accept", "application/json");
        try (CloseableHttpResponse response = client.execute(get)) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200 || response.getEntity() == null) {
                LOGGER.warn("Could not query Apache Reporter (HTTP {}); will not deduplicate.", statusCode);
                return null;
            }
            try (InputStreamReader reader =
                    new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8)) {
                JsonObject root = new Gson().fromJson(reader, JsonObject.class);
                JsonObject releases = root == null ? null : root.getAsJsonObject("releases");
                JsonObject committeeReleases = releases == null ? null : releases.getAsJsonObject(COMMITTEE);
                // the reporter keys each release by its full name (e.g. "Apache Sling API 2.27.6")
                return committeeReleases == null ? Set.of() : Set.copyOf(committeeReleases.keySet());
            }
        } catch (Exception e) {
            LOGGER.warn("Could not query Apache Reporter ({}); will not deduplicate.", e.getMessage());
            return null;
        }
    }

    /**
     * Records a single release with the reporter, dated now.
     *
     * @return {@link Result#ADDED} on success, or {@link Result#ACCESS_DENIED} when the reporter rejects
     *     the write for lack of committee access (a non-PMC / non-ASF-member user)
     * @throws IOException on any other failure (including a non-200 status or an unexpected error body)
     */
    static Result addRelease(CloseableHttpClient client, Release release) throws IOException {
        Instant now = Instant.now();
        String xdate = DateTimeFormatter.ISO_LOCAL_DATE
                .withZone(ZoneId.systemDefault())
                .format(now);
        HttpPost post = new HttpPost(ADD_RELEASE_URL);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("date", Long.toString(now.getEpochSecond())));
        params.add(new BasicNameValuePair("committee", COMMITTEE));
        params.add(new BasicNameValuePair("version", release.getFullName()));
        params.add(new BasicNameValuePair("xdate", xdate));
        post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));

        try (CloseableHttpResponse response = client.execute(post)) {
            int statusCode = response.getStatusLine().getStatusCode();
            // addrelease.py returns HTTP 200 even on failure, with the reason in the body.
            String body = response.getEntity() == null
                    ? ""
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
                            .strip();
            if (statusCode == 200 && !body.contains("Could not save")) {
                return Result.ADDED;
            }
            if (body.toLowerCase().contains("access to this committee data")) {
                return Result.ACCESS_DENIED;
            }
            throw new IOException("Reporter update failed for " + release.getFullName() + ": HTTP " + statusCode
                    + (body.isEmpty() ? "" : " - " + body));
        }
    }
}
