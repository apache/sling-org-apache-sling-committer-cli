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
package org.apache.sling.cli.impl.pgp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.junit.SystemPropertiesRule;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PGPSignatureValidatorTest {

    private static final Map<String, String> SYSTEM_PROPS = new HashMap<>();

    private PGPSignatureValidator pgpSignatureValidator;

    static {
        SYSTEM_PROPS.put("asf.username", "asf-user");
        SYSTEM_PROPS.put("asf.password", "asf-password");
        SYSTEM_PROPS.put("jira.username", "jira-user");
        SYSTEM_PROPS.put("jira.password", "jira-password");
    }

    @Rule
    public final SystemPropertiesRule sysProps = new SystemPropertiesRule(SYSTEM_PROPS);

    @Rule
    public OsgiContext context = new OsgiContext();

    @Test
    public void verifyPGPSignatures() {
        PGPSignatureValidator.ValidationResult result = pgpSignatureValidator.verify(Paths.get("src/test/resources/nexus/orgapachesling-0" +
                "/org/apache/sling/adapter" +
                "-annotations/1.0" +
                ".0/adapter-annotations-1.0.0.pom"), Paths.get("src/test/resources/nexus/orgapachesling-0/org/apache/sling/adapter" +
                "-annotations/1.0.0/adapter-annotations-1.0.0.pom.asc"));
        assertTrue(result.isValid());
        Iterator<String> ids = result.getKey().getUserIDs();
        boolean foundId = false;
        while (ids.hasNext()) {
            if ("Justin Edelson (CODE SIGNING KEY) <justin@apache.org>".equals(ids.next())) {
                foundId = true;
            }
        }
        assertTrue(foundId);
    }

    @Test(expected = IllegalStateException.class)
    public void verifyInvalidPGPSignatures() {
        pgpSignatureValidator.verify(Paths.get("src/test/resources/nexus/orgapachesling-0" +
                        "/org/apache/sling/adapter" +
                        "-annotations/1.0" +
                        ".0/adapter-annotations-1.0.0.pom"),
                Paths.get("src/test/resources/pgp/adapter-annotations-1.0.0.pom.invalid.asc"));
    }

    @Test
    public void testDownload(){
        pgpSignatureValidator = context.registerInjectActivateService(new PGPSignatureValidator(), "sling.keys", "target/downloaded.asc");
        assertNotNull(pgpSignatureValidator.getKeyRingCollection());
        assertTrue(pgpSignatureValidator.getKeyRingCollection().iterator().hasNext());
    }

    @Test
    public void verifyChangedFile() {
        PGPSignatureValidator.ValidationResult result =
                pgpSignatureValidator.verify(Paths.get("src/test/resources/pgp/adapter-annotations-1.0.0.changed.pom"), Paths.get("src" +
                        "/test/resources/nexus/orgapachesling-0/org/apache/sling/adapter" +
                        "-annotations/1.0.0/adapter-annotations-1.0.0.pom.asc"));
        assertFalse(result.isValid());
        Iterator<String> ids = result.getKey().getUserIDs();
        boolean foundId = false;
        while (ids.hasNext()) {
            if ("Justin Edelson (CODE SIGNING KEY) <justin@apache.org>".equals(ids.next())) {
                foundId = true;
            }
        }
        assertTrue(foundId);
    }

    @Before
    public void prepareDependencies() {
        context.registerInjectActivateService(new CredentialsService());
        context.registerInjectActivateService(new HttpClientFactory());
        pgpSignatureValidator = context.registerInjectActivateService(new PGPSignatureValidator(), "sling.keys", "src/test/resources" +
                "/people/sling-keys.asc");
    }

}
