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
package org.apache.sling.cli.impl.nexus;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sling.cli.impl.nexus.PomParser.PomCoordinates;
import org.apache.sling.cli.impl.release.Release;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PomParserTest {

    private static final String PARENT_POM = "org.apache.sling:sling:66";

    private final PomParser pomParser = new PomParser();

    @Test
    public void testParsePomInheritsGroupIdAndVersionFromParent() {
        // a child module POM omitting <groupId>/<version>/<packaging> inherits them from <parent>
        PomCoordinates coordinates = parse("<project>"
                + "<name>Apache Sling Child</name>"
                + "<artifactId>child</artifactId>"
                + "<parent>"
                + "<groupId>org.apache.sling</groupId>"
                + "<artifactId>parent</artifactId>"
                + "<version>3.4.5</version>"
                + "</parent>"
                + "</project>");
        assertNotNull(coordinates);
        assertEquals("org.apache.sling", coordinates.groupId());
        assertEquals("3.4.5", coordinates.version());
        assertEquals("jar", coordinates.packaging());
    }

    @Test
    public void testParsePomInvalidXmlReturnsNull() {
        // malformed XML triggers the error branch which logs and returns null
        assertNull(parse("this is not xml"));
    }

    @Test
    public void testToReleasesSkipsInvalidReleaseNames() {
        // a POM whose name/version cannot be parsed into a Release yields an empty result rather than
        // throwing, exercising the buildReleases error branch
        Set<Release> releases = PomParser.toReleases(
                List.of(pom("", "org.apache.sling", "org.apache.sling.broken", "", "jar", PARENT_POM)));
        assertTrue(releases.isEmpty());
    }

    @Test
    public void testToReleasesSingleModule() {
        // a POM's <name> is the bare component name; the version comes from <version>
        Set<Release> releases = PomParser.toReleases(List.of(
                pom("Apache Sling Foo", "org.apache.sling", "org.apache.sling.foo", "1.2.0", "jar", PARENT_POM)));
        assertEquals(Set.of("Apache Sling Foo 1.2.0"), fullNames(releases));
    }

    @Test
    public void testToReleasesIndependentModulesAreAllReturned() {
        // several unrelated modules staged + voted together: each keeps its own release/JIRA version
        Set<Release> releases = PomParser.toReleases(List.of(
                pom("Apache Sling Foo", "org.apache.sling", "org.apache.sling.foo", "1.2.0", "jar", PARENT_POM),
                pom("Apache Sling Bar", "org.apache.sling", "org.apache.sling.bar", "2.0.0", "jar", PARENT_POM)));
        assertEquals(Set.of("Apache Sling Foo 1.2.0", "Apache Sling Bar 2.0.0"), fullNames(releases));
    }

    @Test
    public void testToReleasesReactorCollapsesToAggregator() {
        // a real reactor: a pom-packaging aggregator that is the parent of the staged child modules.
        // The whole reactor is one logical release -> only the aggregator's name is returned.
        String reactorKey = "org.apache.sling:org.apache.sling.reactor:1.0.0";
        Set<Release> releases = PomParser.toReleases(List.of(
                pom("Apache Sling Reactor", "org.apache.sling", "org.apache.sling.reactor", "1.0.0", "pom", PARENT_POM),
                // children inherit groupId/version from the reactor parent
                pom("Apache Sling Reactor Core", "org.apache.sling", "core", "1.0.0", "jar", reactorKey),
                pom("Apache Sling Reactor API", "org.apache.sling", "api", "1.0.0", "jar", reactorKey)));
        assertEquals(Set.of("Apache Sling Reactor 1.0.0"), fullNames(releases));
    }

    private PomCoordinates parse(String pomXml) {
        try (InputStream stream = new ByteArrayInputStream(pomXml.getBytes(StandardCharsets.UTF_8))) {
            return pomParser.parse(stream, "test.pom");
        } catch (Exception e) {
            throw new AssertionError("Unexpected failure reading the test POM stream.", e);
        }
    }

    private static PomCoordinates pom(
            String name, String groupId, String artifactId, String version, String packaging, String parentKey) {
        return new PomCoordinates(name, groupId, artifactId, version, packaging, parentKey);
    }

    private static Set<String> fullNames(Set<Release> releases) {
        return releases.stream().map(Release::getFullName).collect(Collectors.toSet());
    }
}
