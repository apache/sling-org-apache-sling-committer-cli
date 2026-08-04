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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sling.cli.impl.release.Release;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Parses staged Maven POMs into {@link PomCoordinates} and reduces a set of staged POMs to the
 * releases they represent. Extracted from {@link RepositoryService} so the parsing and reactor
 * aggregation logic can be exercised directly by tests, without reflection.
 */
class PomParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(PomParser.class);

    private final XPathFactory xPathFactory = XPathFactory.newInstance();
    private final DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();

    /**
     * The Maven coordinates and name of a single staged POM, with versions resolved against the
     * {@code <parent>} when a module inherits them.
     */
    record PomCoordinates(
            String name, String groupId, String artifactId, String version, String packaging, String parentKey) {

        /** {@code groupId:artifactId:version} identifying this artifact, or {@code null} if incomplete. */
        String ownKey() {
            return coordinateKey(groupId, artifactId, version);
        }
    }

    /** Parses a single POM stream, returning {@code null} (after logging) when it cannot be read. */
    PomCoordinates parse(InputStream stream, String pomLabel) {
        try {
            XPath xPath = xPathFactory.newXPath();
            DocumentBuilder builder = builderFactory.newDocumentBuilder();
            Document doc = builder.parse(stream);
            String name = xpathString(xPath, doc, "/project/name/text()");
            String artifactId = xpathString(xPath, doc, "/project/artifactId/text()");
            String groupId = xpathString(xPath, doc, "/project/groupId/text()");
            String version = xpathString(xPath, doc, "/project/version/text()");
            String packaging = xpathString(xPath, doc, "/project/packaging/text()");
            String parentGroupId = xpathString(xPath, doc, "/project/parent/groupId/text()");
            String parentArtifactId = xpathString(xPath, doc, "/project/parent/artifactId/text()");
            String parentVersion = xpathString(xPath, doc, "/project/parent/version/text()");
            // In a multi-module reactor a child module's POM frequently omits <groupId>/<version> and
            // inherits them from its <parent>; fall back so such modules are not skipped.
            if (groupId == null || groupId.isBlank()) {
                groupId = parentGroupId;
            }
            if (version == null || version.isBlank()) {
                version = parentVersion;
            }
            if (packaging == null || packaging.isBlank()) {
                packaging = "jar";
            }
            return new PomCoordinates(
                    name,
                    groupId,
                    artifactId,
                    version,
                    packaging,
                    coordinateKey(parentGroupId, parentArtifactId, parentVersion));
        } catch (ParserConfigurationException | SAXException | XPathExpressionException | IOException e) {
            LOGGER.error(String.format("Unable to process pom %s.", pomLabel), e);
            return null;
        }
    }

    /**
     * Reduces the staged POMs to the set of releases they represent.
     *
     * <p>When the staged POMs form a single multi-module reactor — i.e. there is exactly one staged
     * {@code pom}-packaging aggregator that is the {@code <parent>} of other staged modules and is
     * itself the top of the staged hierarchy — the release is that aggregator alone (the reactor is
     * one logical release, tracked by one JIRA version). Otherwise (a single module, or several
     * independent modules staged and voted together) every staged module becomes its own release.
     */
    static Set<Release> toReleases(List<PomCoordinates> poms) {
        Set<String> stagedKeys =
                poms.stream().map(PomCoordinates::ownKey).filter(k -> k != null).collect(Collectors.toSet());
        List<PomCoordinates> aggregators = poms.stream()
                .filter(p -> "pom".equals(p.packaging()))
                // is the parent of at least one other staged module
                .filter(p -> p.ownKey() != null
                        && poms.stream()
                                .anyMatch(other -> other != p && p.ownKey().equals(other.parentKey())))
                // and is the root of the staged hierarchy (its own parent is not itself staged, e.g. it
                // is the shared org.apache.sling parent POM, which is not part of the release)
                .filter(p -> p.parentKey() == null || !stagedKeys.contains(p.parentKey()))
                .toList();
        if (aggregators.size() == 1) {
            return buildReleases(aggregators.get(0));
        }
        Set<Release> releases = new HashSet<>();
        for (PomCoordinates pom : poms) {
            releases.addAll(buildReleases(pom));
        }
        return Set.copyOf(releases);
    }

    /**
     * Returns the artifact ids among {@code poms} that belong to {@code release}.
     *
     * <p>A module's own {@code <name>} identifies it only when it is the release itself. In a multi-module
     * reactor the children carry their own names — <em>Apache Sling Testing Sling Mock Core</em> is not the
     * release <em>Apache Sling Testing Sling Mock</em> — so they are collected through the {@code <parent>}
     * chain instead, which is the same relationship {@link #toReleases(List)} uses to fold a reactor into a
     * single release.
     */
    static Set<String> artifactIdsFor(List<PomCoordinates> poms, Release release) {
        Set<String> roots = poms.stream()
                .filter(p -> buildReleases(p).contains(release))
                .map(PomCoordinates::ownKey)
                .filter(k -> k != null)
                .collect(Collectors.toSet());
        if (roots.isEmpty()) {
            return Set.of();
        }
        // walk down the parent chain until no further module is pulled in, so grandchildren are included too
        Set<String> family = new HashSet<>(roots);
        boolean grown = true;
        while (grown) {
            grown = false;
            for (PomCoordinates pom : poms) {
                String key = pom.ownKey();
                if (key != null && !family.contains(key) && family.contains(pom.parentKey())) {
                    family.add(key);
                    grown = true;
                }
            }
        }
        return poms.stream()
                .filter(p -> p.ownKey() != null && family.contains(p.ownKey()))
                .map(PomCoordinates::artifactId)
                .filter(a -> a != null && !a.isBlank())
                .collect(Collectors.toSet());
    }

    private static Set<Release> buildReleases(PomCoordinates pom) {
        try {
            return new HashSet<>(Release.fromString(pom.name() + " " + pom.version()));
        } catch (IllegalArgumentException e) {
            LOGGER.error(
                    String.format("Unable to determine a valid release from '%s %s'", pom.name(), pom.version()), e);
            return Set.of();
        }
    }

    private static String coordinateKey(String groupId, String artifactId, String version) {
        if (groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank()) {
            return null;
        }
        return groupId + ":" + artifactId + ":" + version;
    }

    private static String xpathString(XPath xPath, Document doc, String expression) throws XPathExpressionException {
        return (String) xPath.compile(expression).evaluate(doc, XPathConstants.STRING);
    }
}
