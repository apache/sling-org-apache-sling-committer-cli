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
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.jira.Issue;
import org.apache.sling.cli.impl.jira.VersionClient;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.people.MembersFinder;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/**
 * Runs all post-vote release finalization steps in sequence. A read-only pre-flight first validates the
 * JIRA state and aborts <em>before</em> any irreversible action if an issue was tagged with the release's
 * fix version only after the artifacts were staged (it cannot be part of the release &mdash; SLING-13260);
 * because nothing has run yet, the operator can fix the tagging in JIRA and simply re-run finalize. The
 * steps are then:
 * <ol>
 *   <li>Upload artifacts to dist.apache.org (only when the current user is a PMC member)</li>
 *   <li>Promote staging repository to Maven Central</li>
 *   <li>Create the next JIRA version and move unresolved issues</li>
 *   <li>Mark the current JIRA version as released</li>
 *   <li>Update the Apache Reporter System</li>
 *   <li>Update the Sling website: the releases list and the downloads page</li>
 * </ol>
 * The only repository-dependent step (dist.apache.org) runs <em>before</em> promotion, which drops the
 * staging repository; every later step is repository-independent. This makes finalize <em>resumable</em>:
 * if it fails partway (e.g. a JIRA hiccup), fix the problem and re-run. Before promotion, resume with
 * {@code --repository}; after promotion the repository is gone, so resume with {@code --release "<name>"}.
 * Each step detects whether it is already done (dist already published, repository already promoted, JIRA
 * version already released, reporter already lists the release) and skips it, so re-running is safe.
 * <p>
 * Uploading to dist.apache.org is only possible for PMC members, so it is performed automatically
 * when the current user (resolved from the ASF credentials) is a PMC member and skipped otherwise.
 * When skipped, a PMC member must complete it separately (the {@code tally-votes} result email asks
 * for this when run by a non-PMC member). The previous version to remove from dist/release is
 * deduced from the current contents of the release directory, restricted to the major version being
 * released so parallel major version streams stay published.
 */
@Component(
        service = Command.class,
        property = {
            Command.PROPERTY_NAME_COMMAND_GROUP + "=" + FinalizeCommand.GROUP,
            Command.PROPERTY_NAME_COMMAND_NAME + "=" + FinalizeCommand.NAME
        })
@CommandLine.Command(
        name = FinalizeCommand.NAME,
        description = "Runs all post-vote finalization steps, in order: update dist.apache.org (PMC members only),"
                + " promote to Maven Central, update JIRA, report to Apache, and update the Sling website.",
        subcommands = CommandLine.HelpCommand.class)
public class FinalizeCommand implements Command {

    static final String GROUP = "release";
    static final String NAME = "finalize";

    private static final Logger LOGGER = LoggerFactory.getLogger(FinalizeCommand.class);

    @CommandLine.Option(
            names = {"-r", "--repository"},
            description = "Nexus staging repository id (initial run, before promotion)")
    private Integer repositoryId;

    @CommandLine.Option(
            names = {"--release"},
            description = "Release name(s) to resume by, e.g. \"Apache Sling Foo 1.2.0\" (comma-separated). Use to"
                    + " resume finalize after the staging repository has been promoted and dropped; completed steps"
                    + " are detected and skipped.")
    private String releaseName;

    @CommandLine.Option(
            names = {"--force-close-late-issues"},
            description = "Close issues even if they were tagged with the release's fix version only after the"
                    + " artifacts were staged. Use only after confirming the fix is actually part of the release"
                    + " (e.g. the fix version was simply forgotten during the release).")
    private boolean forceCloseLateIssues;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    @CommandLine.Mixin
    private SiteCheckoutOptions siteCheckoutOptions;

    @Reference
    private RepositoryService repositoryService;

    @Reference
    private VersionClient versionClient;

    @Reference
    private HttpClientFactory httpClientFactory;

    @Reference
    private CredentialsService credentialsService;

    @Reference
    private MembersFinder membersFinder;

    /** The finalize target: the staging repository (null once it has been promoted and dropped) and its releases. */
    private record FinalizeTarget(StagingRepository repository, Set<Release> releases) {}

    @Override
    public Integer call() {
        try {
            FinalizeTarget target = resolveTarget();
            if (target == null) {
                return CommandLine.ExitCode.USAGE;
            }
            if (target.releases().isEmpty()) {
                LOGGER.error("No releases could be resolved.");
                return CommandLine.ExitCode.USAGE;
            }
            return runFinalize(target, reusableCLIOptions.executionMode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Failed executing command", e);
            return CommandLine.ExitCode.SOFTWARE;
        } catch (Exception e) {
            LOGGER.warn("Failed executing command", e);
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    /**
     * Resolves the target releases. Before promotion, act by {@code --repository} (which still exists); to
     * resume after promotion (which drops the repository), act by {@code --release} name instead. Returns
     * {@code null} (after logging the reason) when neither option resolves a target.
     */
    private FinalizeTarget resolveTarget() throws IOException {
        if (repositoryId != null) {
            StagingRepository repository;
            try {
                repository = repositoryService.find(repositoryId);
            } catch (IllegalArgumentException e) {
                LOGGER.error(
                        "Staging repository {} was not found — it was most likely already promoted and"
                                + " dropped. Resume the remaining steps with --release \"<name>\" instead of"
                                + " --repository.",
                        repositoryId);
                return null;
            }
            return new FinalizeTarget(repository, repositoryService.getReleases(repository));
        }
        if (releaseName != null && !releaseName.isBlank()) {
            return new FinalizeTarget(null, Set.copyOf(Release.fromString(releaseName)));
        }
        LOGGER.error("Provide either --repository <id> (initial run) or --release \"<name>\" (to resume after"
                + " the staging repository has been promoted).");
        return null;
    }

    /** Runs the pre-flight check and the six finalize steps for an already-resolved, non-empty target. */
    private Integer runFinalize(FinalizeTarget target, ExecutionMode mode) throws Exception {
        StagingRepository repository = target.repository();
        Set<Release> releases = target.releases();
        boolean isPmcMember = membersFinder.getCurrentMember().isPMCMember();
        Instant stagedAt = repository != null ? repository.getCreated() : null;

        String releaseNames = releases.stream()
                .map(Release::getFullName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)");
        LOGGER.info("=== Finalizing: {} ===", releaseNames);
        if (repository == null) {
            LOGGER.info("Resuming by release name; steps already completed before promotion are detected and"
                    + " skipped.");
        }

        // Pre-flight: validate JIRA state *before* any irreversible action. Promoting to Maven Central drops
        // the staging repository, so detecting a mis-tagged issue here lets the operator fix JIRA and re-run
        // with nothing promoted or changed yet (SLING-13260). On a resume-by-name run there is no staging
        // timestamp, so this is a no-op — it already passed on the initial run.
        LOGGER.info("--- Pre-flight: validating JIRA state ---");
        if (!preflightJiraState(releases, stagedAt)) {
            LOGGER.error("Aborting finalize before any changes are made. Re-tag the issue(s) to the correct fix"
                    + " version (or re-run with --force-close-late-issues), then run finalize again — nothing has"
                    + " been promoted or changed yet.");
            return CommandLine.ExitCode.SOFTWARE;
        }

        // Step 1: Update dist.apache.org. This is the only repository-dependent step, so it runs *before*
        // promote (which drops the repository); everything after promote is repository-independent and thus
        // resumable by --release. (PMC members only; auto-detected.)
        stepUpdateDistStage(repository, mode, isPmcMember);

        // Step 2: Promote to Maven Central
        repository = stepPromoteStage(repository, mode);

        // Step 3: Create next JIRA version and move unresolved issues (idempotent: skips if the successor
        // already exists / there are no unresolved issues left to move)
        LOGGER.info("--- Step 3/6: Create next JIRA version ---");
        for (Release release : releases) {
            stepCreateNextJiraVersion(release, mode);
        }

        // Step 4: Mark JIRA version as released (idempotent: release() skips an already-released version)
        LOGGER.info("--- Step 4/6: Release JIRA version ---");
        for (Release release : releases) {
            stepReleaseJiraVersion(release, stagedAt, mode);
        }

        // Step 5: Update Apache Reporter (idempotent: skips releases the reporter already lists)
        LOGGER.info("--- Step 5/6: Update Apache Reporter ---");
        if (mode == ExecutionMode.DRY_RUN) {
            LOGGER.info("Would add {} release(s) to the Apache Reporter System", releases.size());
            releases.forEach(r -> LOGGER.info("  - {}", r.getFullName()));
        } else {
            stepUpdateReporter(releases);
        }

        // Step 6: Update the Sling website (releases list and downloads page). Last, because it is the only
        // step that is safe to redo at any time and the only one a non-committer cannot break anything with.
        LOGGER.info("--- Step 6/6: Update the Sling website ---");
        stepUpdateSite(repository, releases, mode);

        LOGGER.info("=== Release finalization complete! ===");
        return CommandLine.ExitCode.OK;
    }

    /**
     * Delegates to {@link UpdateLocalSiteCommand} so the site editing, committing and pushing flow lives in
     * one place. Any failure here - unchecked ones included - is reported but does not fail finalize:
     * everything irreversible has already succeeded by this point, and the website can be updated separately
     * with {@code update-local-site}.
     */
    private void stepUpdateSite(StagingRepository repository, Set<Release> releases, ExecutionMode mode) {
        try {
            UpdateLocalSiteCommand.SiteUpdate update = UpdateLocalSiteCommand.updateLocalSite(
                    repositoryService, repository, releases, siteCheckoutOptions.checkout);
            UpdateLocalSiteCommand.applySiteUpdate(
                    update,
                    siteCheckoutOptions.checkout,
                    mode,
                    credentialsService.getAsfCredentials(),
                    membersFinder.getCurrentMember());
            if (!update.downloadsNotListed().isEmpty()) {
                LOGGER.warn(
                        "The downloads page has no entry for {} — please add it by hand.", update.downloadsNotListed());
            }
        } catch (GitAPIException | IOException | RuntimeException e) {
            LOGGER.warn(
                    "Failed to update the Sling website; run '{} {}' separately to complete it.",
                    UpdateLocalSiteCommand.GROUP,
                    UpdateLocalSiteCommand.NAME,
                    e);
        }
    }

    private void stepUpdateDistStage(StagingRepository repository, ExecutionMode mode, boolean isPmcMember)
            throws IOException {
        if (!isPmcMember) {
            LOGGER.info("--- Step 1/6: Update dist.apache.org --- SKIPPED (current user is not a PMC member;"
                    + " a PMC member must update dist.apache.org separately) ---");
            return;
        }
        LOGGER.info("--- Step 1/6: Update dist.apache.org ---");
        if (repository == null) {
            LOGGER.info("SKIPPED (staging repository already promoted; if dist still needs updating a PMC"
                    + " member must run update-dist separately)");
        } else {
            stepUpdateDist(reusableCLIOptions.executionMode);
        }
    }

    private StagingRepository stepPromoteStage(StagingRepository repository, ExecutionMode mode) throws IOException {
        LOGGER.info("--- Step 2/6: Promote to Maven Central ---");
        if (repository == null) {
            LOGGER.info("SKIPPED (staging repository already promoted and dropped)");
        } else if (mode == ExecutionMode.DRY_RUN) {
            LOGGER.info("Would promote {} to Maven Central", repository.getRepositoryId());
            return repository;
        } else {
            LOGGER.info("Promoting {}...", repository.getRepositoryId());
            repositoryService.promote(repository);
            LOGGER.info("Promoted. Artifacts will appear on Maven Central within ~10 minutes.");
        }
        return null;
    }

    private void stepUpdateDist(ExecutionMode mode) throws IOException {
        UpdateDistCommand.doUpdateDist(repositoryService, repositoryId, null, mode, credentialsService);
    }

    private void stepCreateNextJiraVersion(Release release, ExecutionMode mode) throws IOException {
        JiraVersions.createSuccessorAndMoveUnresolved(versionClient, release, mode, LOGGER);
    }

    /**
     * Validates, before any irreversible action, that no resolved issue acquired a release's fix version
     * only after the artifacts were staged. Logs any offenders.
     *
     * @return {@code true} if finalize may proceed, {@code false} if it must abort
     */
    private boolean preflightJiraState(Set<Release> releases, Instant stagedAt) throws IOException {
        boolean ok = true;
        for (Release release : releases) {
            List<Issue> lateIssues = LateFixVersionGuard.reportLateIssues(
                    versionClient, release, stagedAt, forceCloseLateIssues, LOGGER);
            if (!lateIssues.isEmpty() && !forceCloseLateIssues) {
                ok = false;
            }
        }
        if (ok) {
            LOGGER.info("JIRA state OK.");
        }
        return ok;
    }

    private void stepReleaseJiraVersion(Release release, Instant stagedAt, ExecutionMode mode) throws Exception {
        if (mode == ExecutionMode.DRY_RUN) {
            LOGGER.info("Would mark JIRA version {} as released", release.getFullName());
        } else {
            // pre-flight already validated this; when forcing, also skip the guard inside release()
            versionClient.release(release, forceCloseLateIssues ? null : stagedAt);
            LOGGER.info("Marked JIRA version {} as released", release.getFullName());
        }
    }

    private void stepUpdateReporter(Set<Release> releases) throws IOException {
        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            // Query first so a resumed run does not add a release the reporter already lists.
            Set<String> alreadyRecorded = Reporter.fetchRegisteredReleaseNames(client);
            for (Release release : releases) {
                if (alreadyRecorded.contains(release.getFullName())) {
                    LOGGER.info("Apache Reporter already lists {}; skipping.", release.getFullName());
                    continue;
                }
                // Reporter release data is committee-scoped; a non-PMC / non-ASF-member user cannot add it.
                // Like the dist step, treat that as a skip-with-warning rather than a hard failure.
                if (Reporter.addRelease(client, release) == Reporter.Result.ACCESS_DENIED) {
                    LOGGER.warn(
                            "Apache Reporter NOT updated for {} — the current user lacks committee access; a PMC"
                                    + " member must add it.",
                            release.getFullName());
                } else {
                    LOGGER.info("Updated Apache Reporter for {}", release.getFullName());
                }
            }
        }
    }
}
