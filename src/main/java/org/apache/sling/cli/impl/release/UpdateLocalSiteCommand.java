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

    /** System property, or {@code SLING_CLI_SITE_CHECKOUT} environment variable, overriding the checkout. */
    static final String CHECKOUT_PROPERTY = "sling.cli.site.checkout";

    /** Cloned over https from gitbox so the same ASF credentials that commit to dist.apache.org can push. */
    static final String SITE_GIT_URL = "https://gitbox.apache.org/repos/asf/sling-site.git";

    /** The branch the website is published from. */
    static final String SITE_BRANCH = "master";

    /**
     * Where the site is checked out. Deliberately not under the shared temporary directory: it is committed
     * and pushed from, so a world-writable location would let anything else on the host substitute the
     * content that reaches the website. Resolved per call so it can be redirected, which is also how it is
     * made to outlive a container run instead of being re-cloned every time.
     */
    static String checkoutDir() {
        String configured = System.getProperty(CHECKOUT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("SLING_CLI_SITE_CHECKOUT");
        }
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return Paths.get(System.getProperty("user.home", "."), ".sling-cli", "sling-site")
                .toString();
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateLocalSiteCommand.class);

    @Reference
    private RepositoryService repositoryService;

    @Reference
    private CredentialsService credentialsService;

    @Reference
    private MembersFinder membersFinder;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    @Override
    public Integer call() {
        try {
            Set<Release> releases = resolveReleases(repositoryService);
            if (releases.isEmpty()) {
                LOGGER.error("Provide either --repository or --release.");
                return CommandLine.ExitCode.USAGE;
            }
            StagingRepository repository = repositoryId != null ? repositoryService.find(repositoryId) : null;

            SiteUpdate update = updateLocalSite(repositoryService, repository, releases);
            applySiteUpdate(
                    update,
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
            RepositoryService repositoryService, StagingRepository repository, Set<Release> releases)
            throws GitAPIException, IOException {

        ensureRepo();
        JBakeContentUpdater updater = new JBakeContentUpdater();
        Path templatePath = Paths.get(checkoutDir(), "src", "main", "jbake", "templates", "downloads.tpl");
        Path releasesPath = Paths.get(checkoutDir(), "src", "main", "jbake", "content", "releases.md");

        List<String> notListed = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());

        for (Release release : releases) {
            updater.updateReleases(releasesPath, release.getComponent(), release.getVersion(), now);
            updateDownloadsFor(repositoryService, repository, release, updater, templatePath, notListed);
        }

        String releaseNames =
                releases.stream().map(Release::getFullName).sorted().collect(Collectors.joining(", "));

        printDiff();
        try (Git git = Git.open(new File(checkoutDir()))) {
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
        int alreadyCurrent = 0;
        for (String artifactId : artifactIds) {
            JBakeContentUpdater.DownloadsUpdate result =
                    updater.updateDownloadsByArtifactId(templatePath, artifactId, release.getVersion());
            updated += result.updated();
            otherMajor += result.skippedOtherMajor();
            alreadyCurrent += result.alreadyCurrent();
        }

        if (updated > 0) {
            LOGGER.info("Updated {} downloads.tpl entry/entries for {}", updated, release.getFullName());
        } else if (alreadyCurrent > 0) {
            // a re-run, or a release whose entry someone already updated by hand
            LOGGER.info("downloads.tpl already lists {} at {}; nothing to do.", artifactIds, release.getVersion());
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
        List<String> candidates = UpdateDistCommand.listReleasePomFileNames(release.getVersion());
        if (candidates.isEmpty()) {
            return Set.of();
        }
        return new TreeSet<>(
                repositoryService.getArtifactIdsFromPomUrls(UpdateDistCommand.DIST_RELEASE_URL, candidates, release));
    }

    /** Commits and pushes the site update, honouring the execution mode. */
    static void applySiteUpdate(SiteUpdate update, ExecutionMode mode, Credentials credentials, Member author)
            throws GitAPIException, IOException {

        if (!update.hasChanges()) {
            LOGGER.info("The Sling website is already up to date; nothing to commit.");
            return;
        }
        commitAndPushSiteChanges(
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
            String message, String confirmQuestion, ExecutionMode mode, Credentials credentials, Member author)
            throws GitAPIException, IOException {
        String checkout = checkoutDir();
        switch (mode) {
            case DRY_RUN:
                LOGGER.info(
                        "Would commit the changes above to {} with message \"{}\" and push.", SITE_GIT_URL, message);
                break;
            case INTERACTIVE:
                if (InputOption.YES.equals(UserInput.yesNo(confirmQuestion, InputOption.YES))) {
                    commitAndPush(message, credentials, author);
                } else {
                    LOGGER.info("Aborted; the changes are left in {}.", checkout);
                }
                break;
            case AUTO:
                commitAndPush(message, credentials, author);
                break;
        }
    }

    private static void commitAndPush(String message, Credentials credentials, Member author)
            throws GitAPIException, IOException {
        try (Git git = Git.open(new File(checkoutDir()))) {
            git.add().addFilepattern("src/main/jbake").call();
            // set the committer as well as the author: the container has no git identity, so JGit would
            // otherwise derive one from the process user and hostname (root@<container id>)
            git.commit()
                    .setMessage(message)
                    .setAuthor(author.getName(), author.getEmail())
                    .setCommitter(author.getName(), author.getEmail())
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
    static void printDiff() throws GitAPIException, IOException {
        try (Git git = Git.open(new File(checkoutDir()));
                ByteArrayOutputStream diff = new ByteArrayOutputStream()) {
            git.diff().setOutputStream(diff).call();
            String rendered = diff.toString(StandardCharsets.UTF_8);
            if (!rendered.isBlank()) {
                LOGGER.info("{}{}", System.lineSeparator(), rendered);
            }
        }
    }

    /**
     * Makes sure {@link #checkoutDir()} holds a clean checkout at the tip of {@value #SITE_BRANCH}, so a
     * previous run's leftovers are never committed and the edits apply to current content.
     *
     * <p>{@value #SITE_BRANCH} is checked out explicitly rather than resetting whatever happens to be
     * checked out: the location is configurable, so it may point at a checkout someone else is using, and
     * resetting their branch would discard their work and push the release onto it.
     */
    static void ensureRepo() throws GitAPIException, IOException {

        if (!Paths.get(checkoutDir()).toFile().exists()) {
            createCheckoutParent();
            // Only the tip of the published branch is needed: the site content is edited and committed on
            // top of it, never inspected historically. A full clone of the site repository is a few hundred
            // MB of history against ~15 MB of content, and by default this clone happens on every run since
            // the checkout lives inside the container unless pointed at a directory that outlives it.
            Git.cloneRepository()
                    .setURI(SITE_GIT_URL)
                    .setProgressMonitor(new TextProgressMonitor())
                    .setDirectory(new File(checkoutDir()))
                    .setCloneAllBranches(false)
                    .setBranchesToClone(List.of("refs/heads/" + SITE_BRANCH))
                    .setBranch(SITE_BRANCH)
                    .setDepth(1)
                    .call();
        } else {
            try (Git git = Git.open(new File(checkoutDir()))) {
                // stay shallow on refresh too, so a reused checkout does not grow into a full clone
                git.fetch()
                        .setProgressMonitor(new TextProgressMonitor())
                        .setDepth(1)
                        .call();
                git.checkout().setName(SITE_BRANCH).call();
                git.reset()
                        .setMode(ResetType.HARD)
                        .setRef("origin/" + SITE_BRANCH)
                        .call();
            }
        }
    }

    /**
     * Creates the directory holding the checkout, restricted to its owner where the filesystem supports it,
     * so nothing else can tamper with content that is about to be committed to the website.
     */
    private static void createCheckoutParent() throws IOException {
        Path parent = Paths.get(checkoutDir()).getParent();
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
