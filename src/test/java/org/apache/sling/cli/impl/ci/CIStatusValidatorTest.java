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
package org.apache.sling.cli.impl.ci;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.http.HttpEntity;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.ci.CIStatusValidator.ValidationResult;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.junit.SystemPropertiesRule;
import org.apache.sling.cli.impl.nexus.Artifact;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CIStatusValidatorTest {

    private static final StagingRepository REPOSITORY = mock(StagingRepository.class);
    private static Artifact JAR = new Artifact(REPOSITORY, "org.apache.sling", "sample-artifact", "1.0", "", "jar");
    private static Artifact NON_REPO_POM_ARTIFACT = new Artifact(REPOSITORY, "org.apache.sling", "no-repo-pom", "1.0",
            "", "pom");
    private static Artifact POM_ARTIFACT = new Artifact(REPOSITORY, "org.apache.sling", "repo-pom", "1.0", "", "pom");
    private static final Map<String, String> SYSTEM_PROPS = new HashMap<>();

    @Rule
    public final SystemPropertiesRule sysProps = new SystemPropertiesRule(SYSTEM_PROPS);

    static {
        SYSTEM_PROPS.put("asf.username", "asf-user");
        SYSTEM_PROPS.put("asf.password", "asf-password");
        SYSTEM_PROPS.put("jira.username", "jira-user");
        SYSTEM_PROPS.put("jira.password", "jira-password");
    }

    @Rule
    public OsgiContext context = new OsgiContext();

    private HttpClientFactory clientFactory;
    private CIStatusValidator validator;
    private Map<String, String> urlResourceMap = new HashMap<>();

    @Before
    public void before() throws ClientProtocolException, IOException {
        clientFactory = mock(HttpClientFactory.class);
        CloseableHttpClient httpClient = mock(CloseableHttpClient.class);

        when(httpClient.execute(any(HttpGet.class))).thenAnswer(inv -> {
            HttpGet get = inv.getArgument(0, HttpGet.class);
            CloseableHttpResponse response = mock(CloseableHttpResponse.class);
            if (urlResourceMap.containsKey(get.getURI().toString())) {
                HttpEntity entity = mock(HttpEntity.class);
                when(entity.getContent()).thenReturn(
                        CIStatusValidatorTest.class.getResourceAsStream(urlResourceMap.get(get.getURI().toString())));
                when(response.getEntity()).thenReturn(entity);
            } else {
                throw new IOException("Failed to call URL: " + get.getURI());
            }
            return response;
        });
        when(clientFactory.newClient()).thenReturn(httpClient);

        urlResourceMap.put("https://api.github.com/repos/apache/sling-repo-pom/commits/repo-pom-1.0/status",
                "/ci/failure.json");
        urlResourceMap.put("https://api.github.com/repos/apache/sling-repo-pom/commits/repo-pom-1.1/status",
                "/ci/success.json");
        urlResourceMap.put("https://api.github.com/repos/apache/sling-parent/commits/sling-parent-reactor-47/status",
                "/ci/tag-status.json");
        urlResourceMap.put(
                "https://api.github.com/repos/apache/sling-parent/commits/4d051750e93d473d9918c8498233cd42f0f991e6",
                "/ci/tag-commit.json");
        urlResourceMap.put(
                "https://api.github.com/repos/apache/sling-parent/commits/aa817336d9929371240adac084ec439ad6d185da/status",
                "/ci/parent-status.json");

        context.registerInjectActivateService(new CredentialsService());
        context.registerInjectActivateService(clientFactory);
        validator = context.registerInjectActivateService(new CIStatusValidator());
    }

    private Path getResourcePath(String resourceName) throws URISyntaxException {
        return Path.of(CIStatusValidatorTest.class.getResource(resourceName).toURI());
    }

    @Test
    public void shouldCheck() throws URISyntaxException {
        assertFalse(validator.shouldCheck(JAR, null));
        assertFalse(validator.shouldCheck(NON_REPO_POM_ARTIFACT, getResourcePath("/ci/no-repo.pom")));
        assertTrue(validator.shouldCheck(POM_ARTIFACT, getResourcePath("/ci/repo-1.0.pom")));
    }

    @Test
    public void shouldGetParentCommitForTag() throws URISyntaxException {
        ValidationResult valid = validator
                .isValid(getResourcePath("/ci/tag-test.pom"));
        assertTrue(valid.isValid());
        assertNotNull(valid.getMessage());
    }

    @Test
    public void getCIStatusEndpoint() throws URISyntaxException {
        assertEquals("https://api.github.com/repos/apache/sling-repo-pom/commits/repo-pom-1.0/status",
                validator.getCIStatusEndpoint(getResourcePath("/ci/repo-1.0.pom")));
    }

    @Test
    public void isValid() throws URISyntaxException {
        ValidationResult invalid = validator.isValid(getResourcePath("/ci/repo-1.0.pom"));
        assertFalse(invalid.isValid());
        assertNotNull(invalid.getMessage());

        ValidationResult valid = validator.isValid(getResourcePath("/ci/repo-1.1.pom"));
        assertTrue(valid.isValid());
        assertNotNull(valid.getMessage());
    }

}
