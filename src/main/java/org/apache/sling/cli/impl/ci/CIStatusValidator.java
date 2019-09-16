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
package org.apache.sling.cli.impl.ci;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.nexus.Artifact;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@Component(service = CIStatusValidator.class)
public class CIStatusValidator {

    public static class ValidationResult {
        private final String message;
        private final boolean valid;

        ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        public boolean isValid() {
            return valid;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(CIStatusValidator.class);
    private DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();

    @Reference
    private HttpClientFactory httpClientFactory;

    private XPathFactory xPathFactory = XPathFactory.newInstance();

    protected JsonObject fetchCIStatus(String ciEndpoint) throws IOException {
        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            HttpGet get = new HttpGet(ciEndpoint);
            get.addHeader(HttpHeaders.ACCEPT, "application/json");
            try (CloseableHttpResponse response = client.execute(get)) {
                try (InputStream content = response.getEntity().getContent()) {
                    InputStreamReader reader = new InputStreamReader(content);
                    JsonParser parser = new JsonParser();
                    return parser.parse(reader).getAsJsonObject();
                }
            }
        }
    }

    String getCIEndpoint(Artifact artifact, Path artifactFilePath) {
        log.trace("getCIEndpoint");
        String ciEndpoint = null;
        try {
            DocumentBuilder builder = builderFactory.newDocumentBuilder();
            Document xmlDocument = builder.parse(artifactFilePath.toFile());
            XPath xPath = xPathFactory.newXPath();
            String url = (String) xPath.compile("/project/scm/url/text()").evaluate(xmlDocument, XPathConstants.STRING);
            if (url != null && url.trim().length() > 0) {

                url = url.substring(url.indexOf("?p=") + 3);
                url = url.substring(0, url.indexOf(".git"));
                log.debug("Extracted REPO: {}", url);

                ciEndpoint = String.format("https://api.github.com/repos/apache/%s/commits/%s-%s/status", url,
                        artifact.getArtifactId(), artifact.getVersion());
                log.debug("Loaded CI Endpoint: {}", ciEndpoint);
            }
            log.debug("Retrieved SCM URL: {}", url);
        } catch (XPathExpressionException | SAXException | IOException | ParserConfigurationException e) {
            log.debug("Failed to extract SCM URL", e);
        }
        return ciEndpoint;
    }

    public ValidationResult isValid(Artifact artifact, Path artifactFilePath) {
        log.trace("isValid");

        String ciEndpoint = getCIEndpoint(artifact, artifactFilePath);
        try {
            JsonObject status = fetchCIStatus(ciEndpoint);
            List<String> messageEntries = new ArrayList<>();

            JsonArray statuses = status.get("statuses").getAsJsonArray();
            for (JsonElement it : statuses) {
                JsonObject item = it.getAsJsonObject();
                messageEntries.add("\t" + item.get("context").getAsString());
                messageEntries.add("\t\tState: " + item.get("state").getAsString());
                messageEntries.add("\t\tDescription: " + item.get("description").getAsString());
                messageEntries.add("\t\tSee: " + item.get("target_url").getAsString());
            }
            String message = String.join("\n", messageEntries);
            if ("success".equals(status.get("state").getAsString())) {
                return new ValidationResult(true, message);
            } else {

                return new ValidationResult(false, message);
            }
        } catch (UnsupportedOperationException | IOException e) {
            return new ValidationResult(false, "Failed to get CI Status: " + e.toString());
        }
    }

    public boolean shouldCheck(Artifact artifact, Path artifactFilePath) {
        log.trace("shouldCheck");
        return "pom".equals(artifact.getType()) && getCIEndpoint(artifact, artifactFilePath) != null;
    }
}
