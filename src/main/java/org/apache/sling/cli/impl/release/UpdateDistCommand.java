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
package org.apache.sling.cli.impl.release;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.nexus.Artifact;
import org.apache.sling.cli.impl.nexus.LocalRepository;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
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
import picocli.CommandLine;

/**
 * Publishes a release's staged artifacts to {@code dist/release/sling} on dist.apache.org and removes
 * the previous release, using SVNKit's pure-Java Subversion client over https.
 *
 * <p>This follows the flow used for Maven-based Sling module releases (see the Sling release management
 * guide): the artifacts are downloaded from the Nexus staging repository and committed to
 * {@code dist/release/sling}. Unlike the Sling IDE tooling, Maven releases never stage to
 * {@code dist/dev}. Requires PMC membership to commit to dist.apache.org.
 */
@Component(
        service = Command.class,
        property = {
            Command.PROPERTY_NAME_COMMAND_GROUP + "=" + UpdateDistCommand.GROUP,
            Command.PROPERTY_NAME_COMMAND_NAME + "=" + UpdateDistCommand.NAME
        })
@CommandLine.Command(
        name = UpdateDistCommand.NAME,
        description = "Publishes a release's staged artifacts to dist/release on dist.apache.org and removes the"
                + " previous release. Requires PMC membership.",
        subcommands = CommandLine.HelpCommand.class)
public class UpdateDistCommand implements Command {

    static final String GROUP = "release";
    static final String NAME = "update-dist";

    static final String DIST_RELEASE_URL = "https://dist.apache.org/repos/dist/release/sling/";

    static {
        // register the http(s):// DAV repository factory used by all SVNKit operations below
        DAVRepositoryFactory.setup();
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateDistCommand.class);

    @CommandLine.Option(
            names = {"-r", "--repository"},
            description = "Nexus staging repository id",
            required = true)
    private Integer repositoryId;

    @CommandLine.Option(
            names = {"--previous-version"},
            description = "Previous release version to remove from dist/release (e.g. 1.0.0)."
                    + " Optional: if omitted, the closest older version with the same major version currently in"
                    + " dist/release is removed; other major version streams are left untouched.")
    private String previousVersion;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    @Reference
    private RepositoryService repositoryService;

    @Reference
    private CredentialsService credentialsService;

    @Override
    public Integer call() {
        try {
            DistReleasePlan plan =
                    planDistRelease(repositoryService, repositoryService.find(repositoryId), previousVersion);

            if (plan.alreadyPublished()) {
                LOGGER.info(
                        "dist/release already contains {} {}; nothing to do.", plan.artifactId(), plan.newVersion());
                return CommandLine.ExitCode.OK;
            }
            if (plan.newFiles().isEmpty()) {
                LOGGER.warn("No artifacts were downloaded for staging repository {}.", repositoryId);
                return CommandLine.ExitCode.USAGE;
            }

            switch (reusableCLIOptions.executionMode) {
                case DRY_RUN:
                    LOGGER.info(
                            "Would publish {} file(s) to dist/release for {} {}:",
                            plan.newFiles().size(),
                            plan.artifactId(),
                            plan.newVersion());
                    plan.newFiles().forEach(f -> LOGGER.info("  put {} -> {}{}", f, DIST_RELEASE_URL, f.getFileName()));
                    if (!plan.oldFiles().isEmpty()) {
                        LOGGER.info(
                                "Would remove {} old file(s) from dist/release:",
                                plan.oldFiles().size());
                        plan.oldFiles().forEach(f -> LOGGER.info("  rm {}", DIST_RELEASE_URL + f));
                    }
                    break;
                case INTERACTIVE:
                    String question = String.format(
                            "Publish %d file(s) for %s %s to dist/release and remove %d older file(s) for %s?",
                            plan.newFiles().size(),
                            plan.artifactId(),
                            plan.newVersion(),
                            plan.oldFiles().size(),
                            plan.artifactId());
                    if (InputOption.YES.equals(UserInput.yesNo(question, InputOption.YES))) {
                        publishToDistRelease(
                                plan.artifactId(),
                                plan.newVersion(),
                                plan.newFiles(),
                                plan.oldFiles(),
                                credentialsService.getAsfCredentials());
                    } else {
                        LOGGER.info("Aborted.");
                    }
                    break;
                case AUTO:
                    publishToDistRelease(
                            plan.artifactId(),
                            plan.newVersion(),
                            plan.newFiles(),
                            plan.oldFiles(),
                            credentialsService.getAsfCredentials());
                    break;
            }
        } catch (IOException e) {
            LOGGER.warn("Failed executing command", e);
            return CommandLine.ExitCode.SOFTWARE;
        }
        return CommandLine.ExitCode.OK;
    }

    /** What to publish to and remove from dist/release for one staged release. */
    record DistReleasePlan(
            String artifactId,
            String newVersion,
            List<Path> newFiles,
            List<String> oldFiles,
            boolean alreadyPublished) {}

    /**
     * Downloads the staged artifacts and works out what to publish to and remove from {@code dist/release}.
     * Shared by this command and {@link FinalizeCommand} so the flow is not duplicated. When the version is
     * already present in {@code dist/release} the returned plan is marked {@link DistReleasePlan#alreadyPublished()}.
     */
    static DistReleasePlan planDistRelease(
            RepositoryService repositoryService, StagingRepository repository, String previousVersion)
            throws IOException {
        LocalRepository localRepository = repositoryService.download(repository);
        Artifact primary = localRepository.getArtifacts().stream()
                .filter(a -> "pom".equals(a.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No POM artifact found in staging repository"));
        String artifactId = primary.getArtifactId();
        String newVersion = primary.getVersion();
        if (isVersionPublished(artifactId, newVersion)) {
            return new DistReleasePlan(artifactId, newVersion, List.of(), List.of(), true);
        }
        List<Path> newFiles = collectDownloadedFiles(localRepository.getRootFolder());
        List<String> oldFiles = listPreviousReleaseFiles(artifactId, newVersion, previousVersion);
        return new DistReleasePlan(artifactId, newVersion, newFiles, oldFiles, false);
    }

    /**
     * Collects every regular file downloaded from the staging repository (each artifact together with
     * its {@code .asc} signature and checksum sidecars), which is the exact set to publish to the flat
     * {@code dist/release/sling} directory.
     */
    static List<Path> collectDownloadedFiles(Path rootFolder) throws IOException {
        try (Stream<Path> paths = Files.walk(rootFolder)) {
            return paths.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /**
     * Commits {@code newFiles} into {@code dist/release/sling} and removes {@code oldFiles} from it in a
     * single atomic revision, using SVNKit over https. Files are published flat, keyed by their file
     * name (the {@code dist/release/sling} directory holds no sub-directories).
     */
    static void publishToDistRelease(
            String artifactId, String newVersion, List<Path> newFiles, List<String> oldFiles, Credentials credentials)
            throws IOException {
        publishToDistRelease(artifactId, newVersion, newFiles, oldFiles, credentials, DIST_RELEASE_URL);
    }

    static void publishToDistRelease(
            String artifactId,
            String newVersion,
            List<Path> newFiles,
            List<String> oldFiles,
            Credentials credentials,
            String releaseBaseUrl)
            throws IOException {
        Logger logger = LoggerFactory.getLogger(UpdateDistCommand.class);
        try {
            SVNRepository repository =
                    SVNRepositoryFactory.create(SVNURL.parseURIEncoded(stripTrailingSlash(releaseBaseUrl)));
            repository.setAuthenticationManager(
                    new BasicAuthenticationManager(credentials.getUsername(), credentials.getPassword()));
            ISVNEditor editor = repository.getCommitEditor("Release " + artifactId + " " + newVersion, null);
            commitFiles(editor, newFiles, oldFiles, logger);
        } catch (SVNException e) {
            throw new IOException("Failed to update dist.apache.org", e);
        }
    }

    /**
     * Drives the commit editor to add {@code newFiles} and delete {@code oldFiles} in a single
     * revision, aborting the edit if anything fails so a partial commit is never left behind.
     */
    private static void commitFiles(ISVNEditor editor, List<Path> newFiles, List<String> oldFiles, Logger logger)
            throws SVNException, IOException {
        SVNDeltaGenerator deltaGenerator = new SVNDeltaGenerator();
        try {
            editor.openRoot(-1);
            logger.info("Publishing {} file(s) to dist/release...", newFiles.size());
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
                logger.info("Removing {} superseded file(s) from dist/release...", oldFiles.size());
            }
            editor.closeDir();
            SVNCommitInfo info = editor.closeEdit();
            logger.info("Done. Committed revision {} to dist.apache.org.", info.getNewRevision());
        } catch (SVNException e) {
            editor.abortEdit();
            throw e;
        }
    }

    // possessive quantifiers (++, *+) so the matcher never backtracks and cannot overflow the stack
    private static final Pattern LEADING_VERSION = Pattern.compile("^(\\d++(?:\\.\\d++)*+)");

    /**
     * Determines which files to remove from {@code dist/release} when publishing {@code newVersion}
     * of {@code artifactId}. When {@code explicitPreviousVersion} is given, only that version's files
     * are returned. Otherwise only the <em>closest older version with the same major version</em> is
     * returned — the highest version strictly lower than {@code newVersion} sharing its major. This
     * keeps parallel maintenance streams intact (publishing {@code 1.2.16} removes {@code 1.2.14} but
     * keeps {@code 2.1.0}, and publishing {@code 2.1.2} leaves {@code 1.2.14} alone) and never removes
     * a newer version.
     */
    static List<String> listPreviousReleaseFiles(String artifactId, String newVersion, String explicitPreviousVersion)
            throws IOException {
        if (explicitPreviousVersion != null && !explicitPreviousVersion.isBlank()) {
            return listDistFiles(DIST_RELEASE_URL, artifactId + "-" + explicitPreviousVersion);
        }
        List<String> artifactFiles = listDistFiles(DIST_RELEASE_URL, artifactId + "-").stream()
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
     * Returns the highest version among {@code artifactFiles} that shares {@code newVersion}'s major
     * version and is strictly lower than it, or {@code null} if there is none. Versions from other
     * major streams are never candidates, so an older major line stays published.
     */
    private static String closestOlderVersionInSameMajor(
            List<String> artifactFiles, String artifactId, String newVersion) {
        org.osgi.framework.Version target = parseOsgiVersion(newVersion);
        if (target == null) {
            return null;
        }
        String closest = null;
        org.osgi.framework.Version closestVersion = null;
        for (String file : artifactFiles) {
            String candidateName = extractVersion(file, artifactId);
            org.osgi.framework.Version candidate = parseOsgiVersion(candidateName);
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

    private static org.osgi.framework.Version parseOsgiVersion(String version) {
        if (version == null) {
            return null;
        }
        try {
            return new org.osgi.framework.Version(version);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns {@code true} if {@code dist/release/sling} already contains files for the given
     * {@code artifactId} and {@code version}. Used to make publishing idempotent so finalize can be
     * safely re-run.
     */
    static boolean isVersionPublished(String artifactId, String version) throws IOException {
        return listDistFiles(DIST_RELEASE_URL, artifactId + "-" + version).stream()
                .anyMatch(f -> belongsToVersion(f, artifactId, version));
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

    /**
     * Returns the names of every {@code .pom} published in {@code dist/release} for {@code version}, across
     * all artifacts. Used to resolve a release's artifact ids from the released POMs once the staging
     * repository is gone; the caller narrows the candidates down by reading each POM's {@code <name>}.
     */
    static List<String> listReleasePomFileNames(String version) throws IOException {
        return listDistFiles(DIST_RELEASE_URL, "").stream()
                .filter(f -> f.endsWith("-" + version + ".pom"))
                .toList();
    }

    static List<String> listDistFiles(String baseUrl, String prefix) throws IOException {
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

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
