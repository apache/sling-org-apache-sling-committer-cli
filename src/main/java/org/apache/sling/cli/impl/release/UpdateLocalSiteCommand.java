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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.dist.DistRepository;
import org.apache.sling.cli.impl.jbake.JBakeContentUpdater;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.people.Member;
import org.apache.sling.cli.impl.people.MembersFinder;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/**
 * Updates the Sling website with new release information, in a local checkout of the site repository.
 *
 * <p>Two files make up the website side of promoting a release: {@code content/releases.md}, the
 * chronological list of every release, and {@code templates/downloads.tpl}, which drives the downloads
 * page. Both are edited here and committed together, matching how the site is maintained by hand.
 *
 * <p>The downloads page is keyed on the <em>artifact id</em> rather than on the release's component name,
 * because the two routinely differ (<em>Tracer</em> is listed as <em>Log Tracer</em>) and one release can
 * own several entries. Artifact ids come from the staged POMs when a staging repository is given, and from
 * the released POMs on dist.apache.org otherwise, so the command works before and after promotion.
 */
@Component(
        service = Command.class,
        property = {
            Command.PROPERTY_NAME_COMMAND_GROUP + "=" + UpdateLocalSiteCommand.GROUP,
            Command.PROPERTY_NAME_COMMAND_NAME + "=" + UpdateLocalSiteCommand.NAME
        })
@CommandLine.Command(
        name = UpdateLocalSiteCommand.NAME,
        description = "Updates the Sling website with the new release information, based on a local checkout",
        subcommands = CommandLine.HelpCommand.class)
public class UpdateLocalSiteCommand extends AbstractReleaseCommand {

    static final String GROUP = "release";
    static final String NAME = "update-local-site";

    /** Cloned over https from gitbox so the same ASF credentials that commit to dist.apache.org can push. */
    static final String SITE_GIT_URL = "https://gitbox.apache.org/repos/asf/sling-site.git";

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateLocalSiteCommand.class);

    @Reference
    private RepositoryService repositoryService;

    @Reference
    private CredentialsService credentialsService;

    @Reference
    private MembersFinder membersFinder;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    @CommandLine.Mixin
    private SiteCheckoutOptions siteCheckoutOptions;

    @Override
    public Integer call() {
        try {
            Set<Release> releases = resolveReleases(repositoryService);
            if (releases.isEmpty()) {
                LOGGER.error("Provide either --repository or --release.");
                return CommandLine.ExitCode.USAGE;
            }
            StagingRepository repository = repositoryId != null ? repositoryService.find(repositoryId) : null;

            SiteUpdate update = updateLocalSite(repositoryService, repository, releases, siteCheckoutOptions.checkout);
            applySiteUpdate(
                    update,
                    siteCheckoutOptions.checkout,
                    reusableCLIOptions.executionMode,
                    credentialsService.getAsfCredentials(),
                    membersFinder.getCurrentMember());
        } catch (GitAPIException | IOException e) {
            LOGGER.warn("Failed executing command", e);
            return CommandLine.ExitCode.SOFTWARE;
        }
        return CommandLine.ExitCode.OK;
    }

    /**
     * The result of editing the site checkout.
     *
     * @param hasChanges          whether the checkout has anything to commit
     * @param releaseNames        the releases the edit covered, for the commit message
     * @param downloadsNotListed  releases with no downloads-page entry at all, which a human must add;
     *                            releases skipped because the page tracks another major version are not
     *                            listed here, since for those there is legitimately nothing to do
     */
    record SiteUpdate(boolean hasChanges, String releaseNames, List<String> downloadsNotListed) {}

    /**
     * Clones or refreshes the site checkout and applies the release information to {@code releases.md} and
     * {@code downloads.tpl}. Nothing is committed; shared with {@link FinalizeCommand} so the editing flow
     * is not duplicated there.
     */
    static SiteUpdate updateLocalSite(
            RepositoryService repositoryService, StagingRepository repository, Set<Release> releases, String checkout)
            throws GitAPIException, IOException {

        ensureRepo(checkout);
        JBakeContentUpdater updater = new JBakeContentUpdater();
        Path templatePath = Paths.get(checkout, "src", "main", "jbake", "templates", "downloads.tpl");
        Path releasesPath = Paths.get(checkout, "src", "main", "jbake", "content", "releases.md");

        List<String> notListed = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());

        for (Release release : releases) {
            updater.updateReleases(releasesPath, release.getComponent(), release.getVersion(), now);
            updateDownloadsFor(repositoryService, repository, release, updater, templatePath, notListed);
        }

        String releaseNames =
                releases.stream().map(Release::getFullName).sorted().collect(Collectors.joining(", "));

        printDiff(checkout);
        try (Git git = Git.open(new File(checkout))) {
            boolean hasChanges = !git.status().call().isClean();
            return new SiteUpdate(hasChanges, releaseNames, notListed);
        }
    }

    /** Updates every downloads-page entry belonging to {@code release}, recording why nothing changed. */
    private static void updateDownloadsFor(
            RepositoryService repositoryService,
            StagingRepository repository,
            Release release,
            JBakeContentUpdater updater,
            Path templatePath,
            List<String> notListed)
            throws IOException {

        Set<String> artifactIds = resolveArtifactIds(repositoryService, repository, release);
        if (artifactIds.isEmpty()) {
            LOGGER.warn(
                    "Could not determine the artifact id(s) for {}; downloads.tpl not updated for it.",
                    release.getFullName());
            notListed.add(release.getFullName());
            return;
        }

        int updated = 0;
        int otherMajor = 0;
        for (String artifactId : artifactIds) {
            JBakeContentUpdater.DownloadsUpdate result =
                    updater.updateDownloadsByArtifactId(templatePath, artifactId, release.getVersion());
            updated += result.updated();
            otherMajor += result.skippedOtherMajor();
        }

        if (updated > 0) {
            LOGGER.info("Updated {} downloads.tpl entry/entries for {}", updated, release.getFullName());
        } else if (otherMajor > 0) {
            // dist.apache.org keeps several major streams published while the downloads page lists only the
            // latest; a maintenance release of an older line therefore has nothing to update here
            LOGGER.info(
                    "downloads.tpl lists {} only for another major version; leaving it unchanged for {}.",
                    artifactIds,
                    release.getFullName());
        } else {
            LOGGER.warn(
                    "downloads.tpl has no entry for {} ({}); it may need to be added by hand.",
                    release.getFullName(),
                    artifactIds);
            notListed.add(release.getFullName());
        }
    }

    /**
     * Resolves the artifact ids of {@code release}, preferring the staged POMs and falling back to the
     * released POMs on dist.apache.org so a run after promotion still works.
     */
    private static Set<String> resolveArtifactIds(
            RepositoryService repositoryService, StagingRepository repository, Release release) throws IOException {
        if (repository != null) {
            Set<String> staged = repositoryService.getArtifactIds(repository, release);
            if (!staged.isEmpty()) {
                return new TreeSet<>(staged);
            }
        }
        List<String> candidates = DistRepository.listReleasePomFileNames(release.getVersion());
        if (candidates.isEmpty()) {
            return Set.of();
        }
        return new TreeSet<>(
                repositoryService.getArtifactIdsFromPomUrls(DistRepository.DIST_RELEASE_URL, candidates, release));
    }

    /** Commits and pushes the site update, honouring the execution mode. */
    static void applySiteUpdate(
            SiteUpdate update, String checkout, ExecutionMode mode, Credentials credentials, Member author)
            throws GitAPIException, IOException {

        if (!update.hasChanges()) {
            LOGGER.info("The Sling website is already up to date; nothing to commit.");
            return;
        }
        commitAndPushSiteChanges(
                checkout,
                "Released " + update.releaseNames(),
                "Commit the website changes above and push to sling-site?",
                mode,
                credentials,
                author);
    }

    /**
     * Commits everything staged under the site content directory and pushes it, honouring the execution
     * mode. Shared by every command that edits the site checkout.
     */
    static void commitAndPushSiteChanges(
            String checkout,
            String message,
            String confirmQuestion,
            ExecutionMode mode,
            Credentials credentials,
            Member author)
            throws GitAPIException, IOException {
        switch (mode) {
            case DRY_RUN:
                LOGGER.info(
                        "Would commit the changes above to {} with message \"{}\" and push.", SITE_GIT_URL, message);
                break;
            case INTERACTIVE:
                if (InputOption.YES.equals(UserInput.yesNo(confirmQuestion, InputOption.YES))) {
                    commitAndPush(checkout, message, credentials, author);
                } else {
                    LOGGER.info("Aborted; the changes are left in {}.", checkout);
                }
                break;
            case AUTO:
                commitAndPush(checkout, message, credentials, author);
                break;
        }
    }

    private static void commitAndPush(String checkout, String message, Credentials credentials, Member author)
            throws GitAPIException, IOException {
        try (Git git = Git.open(new File(checkout))) {
            git.add().addFilepattern("src/main/jbake").call();
            git.commit()
                    .setMessage(message)
                    .setAuthor(author.getName(), author.getEmail())
                    .call();
            git.push()
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                            credentials.getUsername(), credentials.getPassword()))
                    .setProgressMonitor(new TextProgressMonitor())
                    .call();
            LOGGER.info("Pushed the website update to {}.", SITE_GIT_URL);
        }
    }

    /** Logs the working tree diff of the site checkout, so the operator can review it before it is pushed. */
    static void printDiff(String checkout) throws GitAPIException, IOException {
        try (Git git = Git.open(new File(checkout));
                ByteArrayOutputStream diff = new ByteArrayOutputStream()) {
            git.diff().setOutputStream(diff).call();
            String rendered = diff.toString(StandardCharsets.UTF_8);
            if (!rendered.isBlank()) {
                LOGGER.info("{}{}", System.lineSeparator(), rendered);
            }
        }
    }

    /**
     * Makes sure the checkout holds a clean checkout at the tip of the default branch, so a
     * previous run's leftovers are never committed and the edits apply to current content.
     */
    static void ensureRepo(String checkout) throws GitAPIException, IOException {

        if (!Paths.get(checkout).toFile().exists()) {
            createCheckoutParent(checkout);
            Git.cloneRepository()
                    .setURI(SITE_GIT_URL)
                    .setProgressMonitor(new TextProgressMonitor())
                    .setDirectory(new File(checkout))
                    .call();
        } else {
            try (Git git = Git.open(new File(checkout))) {
                git.fetch().setProgressMonitor(new TextProgressMonitor()).call();
                git.reset().setMode(ResetType.HARD).setRef("origin/master").call();
            }
        }
    }

    /**
     * Creates the directory holding the checkout, restricted to its owner where the filesystem supports it,
     * so nothing else can tamper with content that is about to be committed to the website.
     */
    private static void createCheckoutParent(String checkout) throws IOException {
        Path parent = Paths.get(checkout).getParent();
        if (parent == null || Files.exists(parent)) {
            return;
        }
        try {
            Files.createDirectories(
                    parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            // non-POSIX filesystem: the permissions cannot be set up front
            Files.createDirectories(parent);
        }
    }
}
