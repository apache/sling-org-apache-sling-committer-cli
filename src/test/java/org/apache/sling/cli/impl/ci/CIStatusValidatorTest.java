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

import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;

import org.apache.sling.cli.impl.ci.CIStatusValidator.ValidationResult;
import org.apache.sling.cli.impl.nexus.Artifact;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class CIStatusValidatorTest {

    private CIStatusValidator validator = new CIStatusValidator() {

        protected JsonObject fetchCIStatus(String ciEndpoint) throws UnsupportedOperationException {
            InputStreamReader reader = null;
            if ("https://api.github.com/repos/apache/sling-repo-pom/commits/repo-pom-1.0/status".equals(ciEndpoint)) {
                reader = new InputStreamReader(CIStatusValidatorTest.class.getResourceAsStream("/ci/failure.json"));
            } else if ("https://api.github.com/repos/apache/sling-repo-pom/commits/successful-pom-1.0/status"
                    .equals(ciEndpoint)) {
                reader = new InputStreamReader(CIStatusValidatorTest.class.getResourceAsStream("/ci/success.json"));
            }
            if (reader == null) {
                throw new NullPointerException("No reader was found for " + ciEndpoint);
            }
            JsonParser parser = new JsonParser();
            return parser.parse(reader).getAsJsonObject();
        }

    };
    private static final StagingRepository REPOSITORY = mock(StagingRepository.class);
    private static Artifact JAR = new Artifact(REPOSITORY, "org.apache.sling", "sample-artifact", "1.0", "", "jar");
    private static Artifact NON_REPO_POM_ARTIFACT = new Artifact(REPOSITORY, "org.apache.sling", "no-repo-pom", "1.0", "", "pom");
    private static Path NON_REPO_POM_FILE;
    private static Artifact POM_ARTIFACT = new Artifact(REPOSITORY, "org.apache.sling", "repo-pom", "1.0", "", "pom");
    private static Artifact SUCCESSFUL_POM_ARTIFACT = new Artifact(REPOSITORY, "org.apache.sling", "successful-pom", "1.0", "",
            "pom");
    private static Path POM_FILE;

    static {
        try {
            URI nonrepo = CIStatusValidatorTest.class.getResource("/ci/no-repo.pom").toURI();
            NON_REPO_POM_FILE = Path.of(nonrepo);
            URI repo = CIStatusValidatorTest.class.getResource("/ci/repo.pom").toURI();
            POM_FILE = Path.of(repo);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void shouldCheck() {
        assertFalse(validator.shouldCheck(JAR, null));
        assertFalse(validator.shouldCheck(NON_REPO_POM_ARTIFACT, NON_REPO_POM_FILE));
        assertTrue(validator.shouldCheck(POM_ARTIFACT, POM_FILE));
    }

    @Test
    public void getCIEndpoint() {
        assertEquals("https://api.github.com/repos/apache/sling-repo-pom/commits/repo-pom-1.0/status",
                validator.getCIEndpoint(POM_ARTIFACT, POM_FILE));
    }

    @Test
    public void isValid() {
        ValidationResult invalid = validator.isValid(POM_ARTIFACT, POM_FILE);
        assertFalse(invalid.isValid());
        assertNotNull(invalid.getMessage());

        ValidationResult valid = validator.isValid(SUCCESSFUL_POM_ARTIFACT, POM_FILE);
        assertTrue(valid.isValid());
        assertNotNull(valid.getMessage());
    }

}
