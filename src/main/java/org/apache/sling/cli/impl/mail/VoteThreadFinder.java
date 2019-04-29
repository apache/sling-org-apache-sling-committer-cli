/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.cli.impl.mail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.osgi.service.component.annotations.Component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

@Component(service = VoteThreadFinder.class)
public class VoteThreadFinder {

    public List<Email> findVoteThread(String releaseName) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String threadSubject = "[VOTE] Release " + releaseName;
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
                    List<Email> emails = new ArrayList<>();
                    JsonElement emailsJson = parser.parse(reader).getAsJsonObject().get("emails");
                    if (emailsJson == null) {
                        throw new IllegalStateException(String.format("Unable to correctly parse JSON from %s. Missing \"emails\" " +
                                "property in the JSON response.", uri.toString()));
                    }
                    if (emailsJson.isJsonArray()) {
                        JsonArray emailsArray = emailsJson.getAsJsonArray();
                        for (JsonElement email : emailsArray) {
                            emails.add(new Email(email.getAsJsonObject().get("id").getAsString()));
                        }
                    }
                    return emails;
                }
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
