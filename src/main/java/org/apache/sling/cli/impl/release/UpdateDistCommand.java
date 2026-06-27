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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.nexus.Artifact;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/**
 * Moves release artifacts from dist/dev to dist/release on dist.apache.org and removes the
 * previous release. Uses svnmucc for atomic SVN operations.
 * Requires PMC membership to commit to dist.apache.org.
 */
@Component(
        service = Command.class,
        property = {
            Command.PROPERTY_NAME_COMMAND_GROUP + "=" + UpdateDistCommand.GROUP,
            Command.PROPERTY_NAME_COMMAND_NAME + "=" + UpdateDistCommand.NAME
        })
@CommandLine.Command(
        name = UpdateDistCommand.NAME,
        description = "Moves release artifacts from dist/dev to dist/release on dist.apache.org and removes the"
                + " previous release. Requires PMC membership.",
        subcommands = CommandLine.HelpCommand.class)
public class UpdateDistCommand implements Command {

    static final String GROUP = "release";
    static final String NAME = "update-dist";

    static final String DIST_DEV_URL = "https://dist.apache.org/repos/dist/dev/sling/";
    static final String DIST_RELEASE_URL = "https://dist.apache.org/repos/dist/release/sling/";

    // Invoke svn/svnmucc by absolute path so execution never relies on PATH resolution, which an
    // attacker could hijack (Sonar java:S4036). Both binaries are provided by the Subversion package
    // installed in the runtime image (see Dockerfile: `apk add subversion`).
    private static final String SVN = "/usr/bin/svn";
    private static final String SVNMUCC = "/usr/bin/svnmucc";

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateDistCommand.class);

    @CommandLine.Option(
            names = {"-r", "--repository"},
            description = "Nexus staging repository id",
            required = true)
    private Integer repositoryId;

    @CommandLine.Option(
            names = {"--previous-version"},
            description = "Previous release version to remove from dist/release (e.g. 1.0.0)."
                    + " Optional: if omitted, all older versions currently in dist/release are removed.")
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
            StagingRepository repository = repositoryService.find(repositoryId);
            Set<Artifact> artifacts = repositoryService.getArtifacts(repository);
            Artifact primary = artifacts.stream()
                    .filter(a -> "pom".equals(a.getType()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No POM artifact found in staging repository"));
            String artifactId = primary.getArtifactId();
            String newVersion = primary.getVersion();

            List<String> newFiles = listSvnFiles(DIST_DEV_URL, artifactId + "-" + newVersion);
            List<String> oldFiles = listPreviousReleaseFiles(artifactId, newVersion, previousVersion);

            if (newFiles.isEmpty()) {
                LOGGER.warn("No files found in {} matching {}-{}", DIST_DEV_URL, artifactId, newVersion);
                LOGGER.warn("Ensure 'mvn release:perform' has staged source-release artifacts to dist/dev.");
                return CommandLine.ExitCode.USAGE;
            }

            switch (reusableCLIOptions.executionMode) {
                case DRY_RUN:
                    LOGGER.info("Would move {} file(s) from dist/dev to dist/release:", newFiles.size());
                    newFiles.forEach(f -> LOGGER.info("  mv {} -> {}", DIST_DEV_URL + f, DIST_RELEASE_URL + f));
                    if (!oldFiles.isEmpty()) {
                        LOGGER.info("Would remove {} old file(s) from dist/release:", oldFiles.size());
                        oldFiles.forEach(f -> LOGGER.info("  rm {}", DIST_RELEASE_URL + f));
                    }
                    break;
                case INTERACTIVE:
                    String question = String.format(
                            "Move %d file(s) for %s %s to dist/release and remove %d older file(s) for %s?",
                            newFiles.size(), artifactId, newVersion, oldFiles.size(), artifactId);
                    InputOption answer = UserInput.yesNo(question, InputOption.YES);
                    if (InputOption.YES.equals(answer)) {
                        runSvnMucc(artifactId, newVersion, newFiles, oldFiles, credentialsService.getAsfCredentials());
                    } else {
                        LOGGER.info("Aborted.");
                    }
                    break;
                case AUTO:
                    runSvnMucc(artifactId, newVersion, newFiles, oldFiles, credentialsService.getAsfCredentials());
                    break;
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warn("Failed executing command", e);
            return CommandLine.ExitCode.SOFTWARE;
        }
        return CommandLine.ExitCode.OK;
    }

    static void runSvnMucc(
            String artifactId, String newVersion, List<String> newFiles, List<String> oldFiles, Credentials credentials)
            throws IOException, InterruptedException {
        Logger logger = LoggerFactory.getLogger(UpdateDistCommand.class);

        List<String> cmd = new ArrayList<>();
        cmd.add(SVNMUCC);
        cmd.add("--username");
        cmd.add(credentials.getUsername());
        cmd.add("--password");
        cmd.add(credentials.getPassword());
        cmd.add("--non-interactive");
        cmd.add("-m");
        cmd.add("Release " + artifactId + " " + newVersion);

        for (String file : newFiles) {
            cmd.add("mv");
            cmd.add(DIST_DEV_URL + file);
            cmd.add(DIST_RELEASE_URL + file);
        }
        for (String file : oldFiles) {
            cmd.add("rm");
            cmd.add(DIST_RELEASE_URL + file);
        }

        logger.info("Running svnmucc to update dist.apache.org...");
        int exitCode = new ProcessBuilder(cmd).inheritIO().start().waitFor();
        if (exitCode != 0) {
            throw new IOException("svnmucc failed with exit code " + exitCode);
        }
        logger.info("Done. dist.apache.org has been updated.");
    }

    /**
     * Determines which files to remove from {@code dist/release} when publishing {@code newVersion}
     * of {@code artifactId}. When {@code explicitPreviousVersion} is given, only that version's files
     * are returned; otherwise every older version currently present in {@code dist/release} for this
     * artifact is returned (the release directory holds only the latest release per ASF policy).
     */
    static List<String> listPreviousReleaseFiles(String artifactId, String newVersion, String explicitPreviousVersion)
            throws IOException {
        if (explicitPreviousVersion != null && !explicitPreviousVersion.isBlank()) {
            return listSvnFiles(DIST_RELEASE_URL, artifactId + "-" + explicitPreviousVersion);
        }
        return listSvnFiles(DIST_RELEASE_URL, artifactId + "-").stream()
                // keep only versioned files for this exact artifact (a numeric version component
                // right after the prefix excludes sibling artifacts such as artifactId-extra-...)
                .filter(f -> isVersionedArtifactFile(f, artifactId))
                // never remove the version we are about to publish
                .filter(f -> !belongsToVersion(f, artifactId, newVersion))
                .toList();
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

    static List<String> listSvnFiles(String baseUrl, String prefix) throws IOException {
        List<String> files = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(SVN, "list", "--non-interactive", baseUrl);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                reader.lines()
                        .map(String::trim)
                        .filter(l -> l.startsWith(prefix))
                        .forEach(files::add);
            }
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while listing SVN directory", e);
        }
        return files;
    }
}
