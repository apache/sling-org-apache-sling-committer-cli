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
package org.apache.sling.cli.impl.jbake;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffEntry.Side;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class JBakeContentUpdaterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private JBakeContentUpdater updater;

    @Before
    public void setUp() throws IOException {

        updater = new JBakeContentUpdater();
        // copy the file away so we don't modify what is in source control
        Files.copy(
                getClass().getResourceAsStream("/downloads.tpl"),
                Paths.get(new File(tmp.getRoot(), "downloads.tpl").toURI()));
        Files.copy(
                getClass().getResourceAsStream("/releases.md"),
                Paths.get(new File(tmp.getRoot(), "releases.md").toURI()));
        Files.copy(getClass().getResourceAsStream("/news.md"), Paths.get(new File(tmp.getRoot(), "news.md").toURI()));
    }

    private Path templatePath() {
        return Paths.get(new File(tmp.getRoot(), "downloads.tpl").toURI());
    }

    private Path newsPath() {
        return Paths.get(new File(tmp.getRoot(), "news.md").toURI());
    }

    private List<String> templateLines() throws IOException {
        return Files.readAllLines(templatePath(), StandardCharsets.UTF_8);
    }

    /** Returns the single template line declaring {@code artifactId}, failing if there is not exactly one. */
    private String lineFor(String artifactId) throws IOException {
        List<String> matches = templateLines().stream()
                .filter(l -> l.contains("|" + artifactId + "|"))
                .toList();
        assertThat("expected exactly one line for " + artifactId, matches.size(), equalTo(1));
        return matches.get(0);
    }

    @Test
    public void updateDownloadsByArtifactId_matchesWhenDisplayNameHasDigits() throws IOException {
        // "I18n" / org.apache.sling.i18n: both the display name and the artifact id contain digits, which
        // the display-name based matching could never handle
        JBakeContentUpdater.DownloadsUpdate result =
                updater.updateDownloadsByArtifactId(templatePath(), "org.apache.sling.i18n", "2.5.14");

        assertThat(result.updated(), equalTo(1));
        assertThat(result.skippedOtherMajor(), equalTo(0));
        assertThat(lineFor("org.apache.sling.i18n"), containsString("|2.5.14|"));
    }

    @Test
    public void updateDownloadsByArtifactId_updatesEntryWithDescriptionColumn() throws IOException {
        // the IDE tooling entry carries a description column, so the version is not simply the third column
        JBakeContentUpdater.DownloadsUpdate result =
                updater.updateDownloadsByArtifactId(templatePath(), "eclipse", "1.4.0");

        assertThat(result.updated(), equalTo(1));
        assertThat(lineFor("eclipse"), containsString("|1.4.0|"));
    }

    @Test
    public void updateDownloadsByArtifactId_updatesEveryArtifactOfAMultiModuleRelease() throws IOException {
        // Testing OSGi Mock is released as one unit but listed as three entries, one per artifact
        for (String artifactId : Arrays.asList(
                "org.apache.sling.testing.osgi-mock.core",
                "org.apache.sling.testing.osgi-mock.junit4",
                "org.apache.sling.testing.osgi-mock.junit5")) {
            assertThat(
                    updater.updateDownloadsByArtifactId(templatePath(), artifactId, "2.4.8")
                            .updated(),
                    equalTo(1));
            assertThat(lineFor(artifactId), containsString("|2.4.8|"));
        }
    }

    @Test
    public void updateDownloadsByArtifactId_leavesOtherMajorVersionAlone() throws IOException {
        // dist.apache.org keeps several major streams published while the downloads page lists only the
        // latest, so releasing 2.0.0 must not rewrite the 1.6.6 entry into a different major version
        String before = lineFor("org.apache.sling.resourceresolver");

        JBakeContentUpdater.DownloadsUpdate result =
                updater.updateDownloadsByArtifactId(templatePath(), "org.apache.sling.resourceresolver", "2.0.0");

        assertThat(result.updated(), equalTo(0));
        assertThat(result.skippedOtherMajor(), equalTo(1));
        assertThat(
                "the entry must not have been touched", lineFor("org.apache.sling.resourceresolver"), equalTo(before));
    }

    @Test
    public void updateDownloadsByArtifactId_updatesMaintenanceReleaseOfTheListedMajor() throws IOException {
        JBakeContentUpdater.DownloadsUpdate result =
                updater.updateDownloadsByArtifactId(templatePath(), "org.apache.sling.resourceresolver", "1.6.8");

        assertThat(result.updated(), equalTo(1));
        assertThat(result.skippedOtherMajor(), equalTo(0));
        assertThat(lineFor("org.apache.sling.resourceresolver"), containsString("|1.6.8|"));
    }

    @Test
    public void updateDownloadsByArtifactId_reportsAnArtifactThatIsNotListed() throws IOException {
        JBakeContentUpdater.DownloadsUpdate result =
                updater.updateDownloadsByArtifactId(templatePath(), "org.apache.sling.not.on.the.page", "1.0.0");

        assertThat(result.updated(), equalTo(0));
        assertThat(result.skippedOtherMajor(), equalTo(0));
        assertTrue("should be reported as not listed", result.notListed());
    }

    @Test
    public void updateDownloadsByArtifactId_isIdempotent() throws IOException {
        updater.updateDownloadsByArtifactId(templatePath(), "org.apache.sling.api", "2.20.2");

        JBakeContentUpdater.DownloadsUpdate second =
                updater.updateDownloadsByArtifactId(templatePath(), "org.apache.sling.api", "2.20.2");

        assertThat("re-running must not report a change", second.updated(), equalTo(0));
        assertThat("but the entry must be recognised as present", second.alreadyCurrent(), equalTo(1));
        assertTrue("an existing entry is not 'not listed'", !second.notListed());
        assertThat(lineFor("org.apache.sling.api"), containsString("|2.20.2|"));
    }

    @Test
    public void updateNews_addsEntryAboveTheExistingOnes() throws IOException {
        boolean added = updater.updateNews(
                newsPath(),
                "Apache Sling Pipes 4.5.2",
                "/documentation/bundles/sling-pipes.html",
                LocalDateTime.of(2026, 8, 4, 12, 0));

        assertTrue(added);
        List<String> lines = Files.readAllLines(newsPath(), StandardCharsets.UTF_8);
        String firstEntry =
                lines.stream().filter(l -> l.startsWith("* ")).findFirst().orElseThrow();
        assertThat(
                firstEntry,
                equalTo("* Released [Apache Sling Pipes 4.5.2](/documentation/bundles/sling-pipes.html)"
                        + " (August 4th, 2026)."));
    }

    @Test
    public void updateNews_withoutLinkOmitsTheMarkdownLink() throws IOException {
        updater.updateNews(newsPath(), "Apache Sling Pipes 4.5.2", null, LocalDateTime.of(2026, 8, 1, 12, 0));

        List<String> lines = Files.readAllLines(newsPath(), StandardCharsets.UTF_8);
        assertThat(
                lines.stream().filter(l -> l.startsWith("* ")).findFirst().orElseThrow(),
                equalTo("* Released Apache Sling Pipes 4.5.2 (August 1st, 2026)."));
    }

    @Test
    public void updateNews_doesNotAnnounceTheSameReleaseTwice() throws IOException {
        LocalDateTime when = LocalDateTime.of(2026, 8, 4, 12, 0);
        assertTrue(updater.updateNews(newsPath(), "Apache Sling Pipes 4.5.2", null, when));

        assertThat(
                "an already announced release must not be added again",
                updater.updateNews(newsPath(), "Apache Sling Pipes 4.5.2", null, when),
                equalTo(false));
        assertThat(
                Files.readAllLines(newsPath(), StandardCharsets.UTF_8).stream()
                        .filter(l -> l.contains("Apache Sling Pipes 4.5.2"))
                        .count(),
                equalTo(1L));
    }

    @Test
    public void updateDownloadsByArtifactId_updatesMavenPluginEntry() throws IOException {
        // maven plugin entries carry a bare artifact id rather than a fully qualified one
        JBakeContentUpdater.DownloadsUpdate result =
                updater.updateDownloadsByArtifactId(templatePath(), "slingstart-maven-plugin", "1.9.0");

        assertThat(result.updated(), equalTo(1));
        assertThat(lineFor("slingstart-maven-plugin"), containsString("|1.9.0|"));
    }

    @Test
    public void updateReleases_releaseInExistingMonth() throws IOException, GitAPIException {
        updateReleases0(
                LocalDateTime.of(2019, 2, 27, 22, 0),
                Arrays.asList(
                        " ",
                        " ## February 2019",
                        " ",
                        "+* API 2.20.2 (27th)",
                        " * DataSource Provider 1.0.4, Resource Collection API 1.0.2, JCR ResourceResolver 3.0.18 (26th)",
                        " * Scripting JSP Tag Library 2.4.0, Scripting JSP Tag Library (Compat) 1.0.0 (18th)",
                        " * Pipes 3.1.0 (15th)"));
    }

    private void updateReleases0(LocalDateTime releaseDate, List<String> expectedLines, String... releaseNameAndInfo)
            throws IOException, GitAPIException {

        if (releaseNameAndInfo.length > 2)
            throw new IllegalArgumentException("Unexpected releaseNameAndInfo: " + Arrays.toString(releaseNameAndInfo));

        String releaseName = releaseNameAndInfo.length > 0 ? releaseNameAndInfo[0] : "API";
        String releaseVersion = releaseNameAndInfo.length > 1 ? releaseNameAndInfo[1] : "2.20.2";

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (Git git = Git.init().setDirectory(tmp.getRoot()).call()) {
            git.add()
                    .addFilepattern("downloads.tpl")
                    .addFilepattern("releases.md")
                    .addFilepattern("news.md")
                    .call();

            git.commit().setMessage("Initial commit").setSign(false).call();

            Path releasesPath = Paths.get(new File(tmp.getRoot(), "releases.md").toURI());
            updater.updateReleases(releasesPath, releaseName, releaseVersion, releaseDate);

            List<DiffEntry> changes = git.diff().setOutputStream(out).call();

            // ensure that the diff we're getting only refers to the releases file
            // alternatively, when no changes are expected validate that

            if (expectedLines.isEmpty()) {
                assertThat("changes.size", changes.size(), equalTo(0));
                return;
            }

            assertThat("changes.size", changes.size(), equalTo(1));
            assertThat("changes[0].type", changes.get(0).getChangeType(), equalTo(ChangeType.MODIFY));
            assertThat("changes[0].path", changes.get(0).getPath(Side.NEW), equalTo("releases.md"));

            // now hack away on it safely
            List<String> ignoredPrefixes = Arrays.asList("diff", "index", "---", "+++", "@@");
            List<String> diffLines = Arrays.stream(new String(out.toByteArray(), StandardCharsets.UTF_8).split("\\n"))
                    .filter(l -> ignoredPrefixes.stream().noneMatch(l::startsWith))
                    .collect(Collectors.toList());

            assertThat(diffLines, contains(expectedLines.toArray(new String[0])));
        }
    }

    @Test
    public void updateReleases_releaseAlreadyExists() throws IOException, GitAPIException {
        updateReleases0(
                LocalDateTime.of(2019, 2, 18, 22, 0), Collections.emptyList(), "Scripting JSP Tag Library", "2.4.0");
    }

    @Test
    public void updateReleases_releaseInNewMonth() throws IOException, GitAPIException {
        updateReleases0(
                LocalDateTime.of(2019, 3, 15, 22, 0),
                Arrays.asList(
                        " ~~~~~~",
                        " This is a list of all our releases, available from our [downloads](/downloads.cgi) page.",
                        " ",
                        "+## March 2019",
                        "+",
                        "+* API 2.20.2 (15th)",
                        "+",
                        " ## February 2019",
                        " ",
                        " * DataSource Provider 1.0.4, Resource Collection API 1.0.2, JCR ResourceResolver 3.0.18 (26th)"));
    }

    @Test
    public void updateReleases_releaseExistingMonthAndDay() throws IOException, GitAPIException {
        updateReleases0(
                LocalDateTime.of(2019, 2, 26, 22, 0),
                Arrays.asList(
                        " ",
                        " ## February 2019",
                        " ",
                        "-* DataSource Provider 1.0.4, Resource Collection API 1.0.2, JCR ResourceResolver 3.0.18 (26th)",
                        "+* DataSource Provider 1.0.4, Resource Collection API 1.0.2, JCR ResourceResolver 3.0.18, API 2.20.2 (26th)",
                        " * Scripting JSP Tag Library 2.4.0, Scripting JSP Tag Library (Compat) 1.0.0 (18th)",
                        " * Pipes 3.1.0 (15th)",
                        " * Testing OSGi Mock 2.4.6 (14th)"));
    }
}
