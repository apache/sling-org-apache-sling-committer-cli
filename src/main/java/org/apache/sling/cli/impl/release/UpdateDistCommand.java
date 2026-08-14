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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.dist.DistRepository;
import org.apache.sling.cli.impl.nexus.Artifact;
import org.apache.sling.cli.impl.nexus.LocalRepository;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
                    plan.newFiles()
                            .forEach(f -> LOGGER.info(
                                    "  put {} -> {}{}", f, DistRepository.DIST_RELEASE_URL, f.getFileName()));
                    if (!plan.oldFiles().isEmpty()) {
                        LOGGER.info(
                                "Would remove {} old file(s) from dist/release:",
                                plan.oldFiles().size());
                        plan.oldFiles().forEach(f -> LOGGER.info("  rm {}", DistRepository.DIST_RELEASE_URL + f));
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
                        DistRepository.publish(
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
                    DistRepository.publish(
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
        if (DistRepository.isVersionPublished(artifactId, newVersion)) {
            return new DistReleasePlan(artifactId, newVersion, List.of(), List.of(), true);
        }
        List<Path> newFiles = collectDownloadedFiles(localRepository.getRootFolder());
        List<String> oldFiles = DistRepository.listPreviousReleaseFiles(artifactId, newVersion, previousVersion);
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
}
