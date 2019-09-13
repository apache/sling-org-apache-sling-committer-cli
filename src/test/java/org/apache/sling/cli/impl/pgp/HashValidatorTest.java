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

import java.nio.file.Paths;

import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HashValidatorTest {

    @Rule
    public OsgiContext context = new OsgiContext();

    private HashValidator hashValidator;

    @Before
    public void before() {
        hashValidator = context.registerInjectActivateService(new HashValidator());
    }

    @Test
    public void testValidHashes() {
        HashValidator.ValidationResult result = hashValidator.validate(Paths.get("src/test/resources/nexus/orgapachesling-0/org/apache" +
                "/sling/adapter-annotations/1.0.0/adapter-annotations-1.0.0.pom" ), Paths.get("src/test/resources/nexus/orgapachesling-0" +
                "/org/apache/sling/adapter-annotations/1.0.0/adapter-annotations-1.0.0.pom.md5"), "MD5");
        assertTrue(result.isValid());

        result = hashValidator.validate(Paths.get("src/test/resources/nexus/orgapachesling-0/org/apache" +
                "/sling/adapter-annotations/1.0.0/adapter-annotations-1.0.0.pom" ), Paths.get("src/test/resources/nexus/orgapachesling-0" +
                "/org/apache/sling/adapter-annotations/1.0.0/adapter-annotations-1.0.0.pom.sha1"), "SHA-1");
        assertTrue(result.isValid());
    }

    @Test
    public void testTamperedFile() {
        HashValidator.ValidationResult result = hashValidator
                .validate(Paths.get("src/test/resources/pgp/adapter-annotations-1.0.0.changed.pom"), Paths.get(
                        "src/test/resources/nexus/orgapachesling-0/org/apache/sling/adapter-annotations/1.0.0/adapter-annotations-1.0.0.pom.md5"),
                        "MD5");
        assertFalse(result.isValid());

        result = hashValidator
                .validate(Paths.get("src/test/resources/pgp/adapter-annotations-1.0.0.changed.pom"), Paths.get(
                        "src/test/resources/nexus/orgapachesling-0/org/apache/sling/adapter-annotations/1.0.0/adapter-annotations-1.0.0.pom.md5"),
                        "SHA-1");
        assertFalse(result.isValid());
    }

    @Test
    public void testChangedHashes() {
        HashValidator.ValidationResult result = hashValidator.validate(Paths.get("src/test/resources/nexus/orgapachesling-0/org/apache" +
                "/sling/adapter-annotations/1.0.0/adapter-annotations-1.0.0.pom" ), Paths.get("src/test/resources/pgp/adapter-annotations" +
                "-1.0.0.pom.changed.md5"), "MD5");
        assertFalse(result.isValid());

        result = hashValidator.validate(Paths.get("src/test/resources/nexus/orgapachesling-0/org/apache" +
                "/sling/adapter-annotations/1.0.0/adapter-annotations-1.0.0.pom" ), Paths.get("src/test/resources/pgp/adapter-annotations" +
                "-1.0.0.pom.changed.sha1"), "SHA-1");
        assertFalse(result.isValid());
    }


}
