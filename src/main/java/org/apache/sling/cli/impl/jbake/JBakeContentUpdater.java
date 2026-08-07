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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class JBakeContentUpdater {

    /**
     * A version column: starts with a digit and contains only version characters. Deliberately does not
     * match a Groovy interpolation such as {@code ${starterVersion}}, which must never be rewritten.
     */
    private static final Pattern VERSION_COLUMN = Pattern.compile("^\\d[\\dA-Za-z.\\-]*$");

    /**
     * Updates the version of every {@code downloads.tpl} entry that declares {@code artifactId}. Keyed on the
     * artifact id because the page's display name often differs from the component name (<em>Tracer</em> is
     * listed as <em>Log Tracer</em>) and one release can own several entries.
     *
     * <p>Only entries on the same major version are rewritten: the page lists one latest-major entry per
     * artifact while {@code dist/release} keeps older majors published, so matching on the artifact id alone
     * would turn a {@code 1.12.18} maintenance release into a downgrade of the {@code 2.x} entry.
     */
    public DownloadsUpdate updateDownloadsByArtifactId(
            Path downloadsTemplatePath, String artifactId, String newReleaseVersion) throws IOException {

        int[] updated = new int[1];
        int[] otherMajor = new int[1];
        int[] alreadyCurrent = new int[1];

        List<String> updatedLines = Files.readAllLines(downloadsTemplatePath, StandardCharsets.UTF_8).stream()
                .map(line -> {
                    String rewritten =
                            updateDownloadsLine(line, artifactId, newReleaseVersion, otherMajor, alreadyCurrent);
                    if (rewritten != null) {
                        updated[0]++;
                        return rewritten;
                    }
                    return line;
                })
                .collect(Collectors.toList());

        Files.write(downloadsTemplatePath, updatedLines);

        return new DownloadsUpdate(updated[0], otherMajor[0], alreadyCurrent[0]);
    }

    /**
     * The outcome of a {@code downloads.tpl} update.
     *
     * @param updated               entries rewritten to the new version
     * @param skippedOtherMajor     entries for the same artifact left alone because they track another major
     *                              version; a non-zero count with {@code updated == 0} means the release is a
     *                              maintenance release of an older line, which the downloads page does not list
     * @param alreadyCurrent        entries that already carried the new version, so a re-run is distinguishable
     *                              from an artifact the page does not list
     */
    public record DownloadsUpdate(int updated, int skippedOtherMajor, int alreadyCurrent) {

        /** {@code true} when the artifact is not listed on the downloads page at all. */
        public boolean notListed() {
            return updated == 0 && skippedOtherMajor == 0 && alreadyCurrent == 0;
        }
    }

    /**
     * Returns the rewritten line, or {@code null} when it does not declare {@code artifactId}, already
     * carries the new version, or tracks a different major version (counted in {@code otherMajor}).
     */
    private String updateDownloadsLine(
            String line, String artifactId, String newReleaseVersion, int[] otherMajor, int[] alreadyCurrent) {
        int quoteStart = line.indexOf('"');
        if (quoteStart == -1) {
            return null;
        }
        int quoteEnd = line.indexOf('"', quoteStart + 1);
        if (quoteEnd == -1) {
            return null;
        }
        String[] columns = line.substring(quoteStart + 1, quoteEnd).split("\\|", -1);

        int artifactColumn = -1;
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equals(artifactId)) {
                artifactColumn = i;
                break;
            }
        }
        if (artifactColumn == -1) {
            return null;
        }
        // entries vary in column count (some carry a description or a file extension), so the version is
        // the first version-shaped column after the artifact id rather than one at a fixed index
        for (int i = artifactColumn + 1; i < columns.length; i++) {
            if (!VERSION_COLUMN.matcher(columns[i]).matches()) {
                continue;
            }
            if (!sameMajor(columns[i], newReleaseVersion)) {
                otherMajor[0]++;
                return null;
            }
            if (columns[i].equals(newReleaseVersion)) {
                alreadyCurrent[0]++;
                return null; // already up to date; not a change, but the entry does exist
            }
            columns[i] = newReleaseVersion;
            return line.substring(0, quoteStart + 1) + String.join("|", columns) + line.substring(quoteEnd);
        }
        return null;
    }

    /**
     * Compares the leading numeric segment of two versions. Handles the composite versions some Sling
     * modules use, where the Sling version is combined with the version of what it wraps, e.g.
     * {@code 4.1.0-1.86.0} for Testing Sling Mock Oak.
     */
    private static boolean sameMajor(String currentVersion, String newVersion) {
        return majorOf(currentVersion).equals(majorOf(newVersion));
    }

    private static String majorOf(String version) {
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) {
            end++;
        }
        return version.substring(0, end);
    }

    public void updateReleases(Path releasesPath, String releaseName, String releaseVersion, LocalDateTime releaseTime)
            throws IOException {

        List<String> releasesLines = Files.readAllLines(releasesPath, StandardCharsets.UTF_8);
        String dateHeader = "## " + releaseTime.format(DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH));

        int releaseLineIdx = -1;
        int dateLineIdx = -1;
        for (int i = 0; i < releasesLines.size(); i++) {
            String releasesLine = releasesLines.get(i);
            if (releasesLine.startsWith("This is a list of all our releases")) {
                releaseLineIdx = i;
            }
            if (releasesLine.equals(dateHeader)) {
                dateLineIdx = i;
            }
        }

        if (dateLineIdx == -1) {
            // need to add month marker
            releasesLines.add(releaseLineIdx + 1, "");
            releasesLines.add(releaseLineIdx + 2, dateHeader);
            releasesLines.add(releaseLineIdx + 3, "");
            dateLineIdx = releaseLineIdx + 2;
        }

        String date = formattedDay(releaseTime);

        // inspect all lines in the current month ( until empty line found )
        // to see if the release date already exists
        boolean changed = false;
        for (int i = dateLineIdx + 2; i < releasesLines.size(); i++) {
            String potentialLine = releasesLines.get(i);
            if (potentialLine.trim().isEmpty()) break;

            if (potentialLine.endsWith("(" + date + ")")) {
                if (potentialLine.contains(releaseName + " " + releaseVersion)) {
                    changed = true;
                    break;
                }

                int insertionIdx = potentialLine.indexOf('(') - 1;
                StringBuilder buffer = new StringBuilder();
                buffer.append(potentialLine.substring(0, insertionIdx))
                        .append(", ")
                        .append(releaseName)
                        .append(' ')
                        .append(releaseVersion)
                        .append(' ')
                        .append(potentialLine.substring(insertionIdx + 1));

                releasesLines.set(i, buffer.toString());
                changed = true;
                break;
            }
        }

        if (!changed) releasesLines.add(dateLineIdx + 2, "* " + releaseName + " " + releaseVersion + " (" + date + ")");

        Files.write(releasesPath, releasesLines);
    }

    /**
     * Prepends a release announcement to the news page, directly above the existing entries.
     *
     * <p>Unlike the releases list, the news page is deliberately not maintained for every release — only
     * releases worth announcing are listed — so this is never run as part of finalizing a release.
     *
     * @param link an optional page the announcement should link to, e.g. {@code /news/sling-14-released.html}
     * @return {@code true} if the entry was added, {@code false} if the news page already announces it
     */
    public boolean updateNews(Path newsPath, String releaseFullName, String link, LocalDateTime releaseTime)
            throws IOException {

        List<String> newsLines = Files.readAllLines(newsPath, StandardCharsets.UTF_8);

        String subject = (link == null || link.isBlank()) ? releaseFullName : "[" + releaseFullName + "](" + link + ")";
        String entry = "* Released " + subject + " ("
                + releaseTime.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)) + " "
                + formattedDay(releaseTime) + ", "
                + releaseTime.format(DateTimeFormatter.ofPattern("uuuu", Locale.ENGLISH)) + ").";

        // an existing announcement may or may not be a link, so match on the release name rather than on
        // the rendered entry
        if (newsLines.stream().anyMatch(l -> l.startsWith("* Released ") && l.contains(releaseFullName))) {
            return false;
        }

        int firstEntryIdx = -1;
        for (int i = 0; i < newsLines.size(); i++) {
            if (newsLines.get(i).startsWith("* ")) {
                firstEntryIdx = i;
                break;
            }
        }
        if (firstEntryIdx == -1) {
            newsLines.add(entry);
        } else {
            newsLines.add(firstEntryIdx, entry);
        }

        Files.write(newsPath, newsLines);
        return true;
    }

    private String formattedDay(LocalDateTime releaseTime) {
        String date = releaseTime.format(DateTimeFormatter.ofPattern("d", Locale.ENGLISH));
        switch (date) {
            case "1":
                return "1st";
            case "2":
                return "2nd";
            case "3":
                return "3rd";
            default:
                return date + "th";
        }
    }
}
