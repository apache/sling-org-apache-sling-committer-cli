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
package org.apache.sling.cli.impl.dist;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sling.cli.impl.Credentials;
import org.osgi.framework.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.BasicAuthenticationManager;
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.io.diff.SVNDeltaGenerator;

/**
 * Reads and writes the Sling release directory on dist.apache.org, using SVNKit's pure-Java Subversion
 * client over https.
 *
 * <p>Distinct from the commands that use it: publishing a release, and resolving a release's artifact ids
 * from the published POMs, both need this access, so it does not belong to any one command.
 *
 * <p>Files live flat in {@code dist/release/sling} — there are no sub-directories — so a file name is
 * enough to identify an entry.
 */
public final class DistRepository {

    public static final String DIST_RELEASE_URL = "https://dist.apache.org/repos/dist/release/sling/";

    static {
        // register the http(s):// DAV repository factory used by all SVNKit operations below
        DAVRepositoryFactory.setup();
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DistRepository.class);

    // possessive quantifiers (++, *+) so the matcher never backtracks and cannot overflow the stack
    private static final Pattern LEADING_VERSION = Pattern.compile("^(\\d++(?:\\.\\d++)*+)");

    private DistRepository() {}

    /** Lists the entries of {@code baseUrl} whose name starts with {@code prefix}. */
    public static List<String> listFiles(String baseUrl, String prefix) throws IOException {
        List<String> files = new ArrayList<>();
        try {
            SVNRepository repository = SVNRepositoryFactory.create(SVNURL.parseURIEncoded(baseUrl));
            long revision = repository.getLatestRevision();
            Collection<SVNDirEntry> entries = new ArrayList<>();
            repository.getDir("", revision, null, entries);
            entries.stream()
                    .map(SVNDirEntry::getName)
                    .filter(name -> name.startsWith(prefix))
                    .forEach(files::add);
        } catch (SVNException e) {
            throw new IOException("Failed to list " + baseUrl, e);
        }
        return files;
    }

    /**
     * Returns the names of every {@code .pom} published for {@code version}, across all artifacts. Used to
     * resolve a release's artifact ids from the released POMs once the staging repository is gone; the
     * caller narrows the candidates down by reading each POM's {@code <name>}.
     */
    public static List<String> listReleasePomFileNames(String version) throws IOException {
        return listFiles(DIST_RELEASE_URL, "").stream()
                .filter(f -> f.endsWith("-" + version + ".pom"))
                .toList();
    }

    /**
     * Returns {@code true} if the release directory already contains files for the given
     * {@code artifactId} and {@code version}. Used to make publishing idempotent so finalize can be
     * safely re-run.
     */
    public static boolean isVersionPublished(String artifactId, String version) throws IOException {
        return listFiles(DIST_RELEASE_URL, artifactId + "-" + version).stream()
                .anyMatch(f -> belongsToVersion(f, artifactId, version));
    }

    /**
     * Determines which files to remove when publishing {@code newVersion} of {@code artifactId}. When
     * {@code explicitPreviousVersion} is given, only that version's files are returned. Otherwise only the
     * <em>closest older version with the same major version</em> is returned — the highest version strictly
     * lower than {@code newVersion} sharing its major. This keeps parallel maintenance streams intact
     * (publishing {@code 1.2.16} removes {@code 1.2.14} but keeps {@code 2.1.0}, and publishing
     * {@code 2.1.2} leaves {@code 1.2.14} alone) and never removes a newer version.
     */
    public static List<String> listPreviousReleaseFiles(
            String artifactId, String newVersion, String explicitPreviousVersion) throws IOException {
        if (explicitPreviousVersion != null && !explicitPreviousVersion.isBlank()) {
            return listFiles(DIST_RELEASE_URL, artifactId + "-" + explicitPreviousVersion);
        }
        List<String> artifactFiles = listFiles(DIST_RELEASE_URL, artifactId + "-").stream()
                // keep only versioned files for this exact artifact (a numeric version component
                // right after the prefix excludes sibling artifacts such as artifactId-extra-...)
                .filter(f -> isVersionedArtifactFile(f, artifactId))
                .toList();
        String previousVersion = closestOlderVersionInSameMajor(artifactFiles, artifactId, newVersion);
        if (previousVersion == null) {
            return List.of();
        }
        return artifactFiles.stream()
                .filter(f -> belongsToVersion(f, artifactId, previousVersion))
                .toList();
    }

    /**
     * Commits {@code newFiles} into the release directory and removes {@code oldFiles} from it in a single
     * atomic revision. Files are published flat, keyed by their file name.
     */
    public static void publish(
            String artifactId, String newVersion, List<Path> newFiles, List<String> oldFiles, Credentials credentials)
            throws IOException {
        publish(artifactId, newVersion, newFiles, oldFiles, credentials, DIST_RELEASE_URL);
    }

    public static void publish(
            String artifactId,
            String newVersion,
            List<Path> newFiles,
            List<String> oldFiles,
            Credentials credentials,
            String releaseBaseUrl)
            throws IOException {
        try {
            SVNRepository repository =
                    SVNRepositoryFactory.create(SVNURL.parseURIEncoded(stripTrailingSlash(releaseBaseUrl)));
            repository.setAuthenticationManager(
                    new BasicAuthenticationManager(credentials.getUsername(), credentials.getPassword()));
            ISVNEditor editor = repository.getCommitEditor("Release " + artifactId + " " + newVersion, null);
            commitFiles(editor, newFiles, oldFiles);
        } catch (SVNException e) {
            throw new IOException("Failed to update dist.apache.org", e);
        }
    }

    /**
     * Drives the commit editor to add {@code newFiles} and delete {@code oldFiles} in a single revision,
     * aborting the edit if anything fails so a partial commit is never left behind.
     */
    private static void commitFiles(ISVNEditor editor, List<Path> newFiles, List<String> oldFiles)
            throws SVNException, IOException {
        SVNDeltaGenerator deltaGenerator = new SVNDeltaGenerator();
        try {
            editor.openRoot(-1);
            LOGGER.info("Publishing {} file(s) to dist/release...", newFiles.size());
            for (Path file : newFiles) {
                String name = file.getFileName().toString();
                editor.addFile(name, null, -1);
                editor.applyTextDelta(name, null);
                String checksum;
                try (InputStream in = Files.newInputStream(file)) {
                    checksum = deltaGenerator.sendDelta(name, in, editor, true);
                }
                editor.closeFile(name, checksum);
            }
            for (String file : oldFiles) {
                editor.deleteEntry(file, -1);
            }
            if (!oldFiles.isEmpty()) {
                LOGGER.info("Removing {} superseded file(s) from dist/release...", oldFiles.size());
            }
            editor.closeDir();
            SVNCommitInfo info = editor.closeEdit();
            LOGGER.info("Done. Committed revision {} to dist.apache.org.", info.getNewRevision());
        } catch (SVNException e) {
            editor.abortEdit();
            throw e;
        }
    }

    /**
     * Returns the highest version among {@code artifactFiles} that shares {@code newVersion}'s major
     * version and is strictly lower than it, or {@code null} if there is none. Versions from other major
     * streams are never candidates, so an older major line stays published.
     */
    private static String closestOlderVersionInSameMajor(
            List<String> artifactFiles, String artifactId, String newVersion) {
        Version target = parseOsgiVersion(newVersion);
        if (target == null) {
            return null;
        }
        String closest = null;
        Version closestVersion = null;
        for (String file : artifactFiles) {
            String candidateName = extractVersion(file, artifactId);
            Version candidate = parseOsgiVersion(candidateName);
            if (candidate == null || candidate.getMajor() != target.getMajor() || candidate.compareTo(target) >= 0) {
                continue;
            }
            if (closestVersion == null || candidate.compareTo(closestVersion) > 0) {
                closestVersion = candidate;
                closest = candidateName;
            }
        }
        return closest;
    }

    private static String extractVersion(String fileName, String artifactId) {
        Matcher matcher = LEADING_VERSION.matcher(fileName.substring(artifactId.length() + 1));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Version parseOsgiVersion(String version) {
        if (version == null) {
            return null;
        }
        try {
            return new Version(version);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isVersionedArtifactFile(String fileName, String artifactId) {
        String prefix = artifactId + "-";
        return fileName.length() > prefix.length()
                && fileName.startsWith(prefix)
                && Character.isDigit(fileName.charAt(prefix.length()));
    }

    private static boolean belongsToVersion(String fileName, String artifactId, String version) {
        String prefix = artifactId + "-" + version;
        if (!fileName.startsWith(prefix)) {
            return false;
        }
        // the version must be the whole name, or be followed by an extension dot or a classifier dash;
        // this avoids matching version 1.0.14 against the longer 1.0.140
        if (fileName.length() == prefix.length()) {
            return true;
        }
        char next = fileName.charAt(prefix.length());
        return next == '.' || next == '-';
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
