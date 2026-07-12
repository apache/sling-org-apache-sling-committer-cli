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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.jira.Issue;
import org.apache.sling.cli.impl.jira.Version;
import org.apache.sling.cli.impl.jira.VersionClient;
import org.apache.sling.cli.impl.nexus.Artifact;
import org.apache.sling.cli.impl.nexus.LocalRepository;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.people.MembersFinder;
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
 * deduced from the current contents of the release directory.
 */
@Component(
        service = Command.class,
        property = {
            Command.PROPERTY_NAME_COMMAND_GROUP + "=" + FinalizeCommand.GROUP,
            Command.PROPERTY_NAME_COMMAND_NAME + "=" + FinalizeCommand.NAME
        })
@CommandLine.Command(
        name = FinalizeCommand.NAME,
        description =
                "Runs all post-vote finalization steps: promote to Maven Central, update JIRA, and report to Apache."
                        + " When the current user is a PMC member, dist.apache.org is updated as well.",
        subcommands = CommandLine.HelpCommand.class)
public class FinalizeCommand implements Command {

    static final String GROUP = "release";
    static final String NAME = "finalize";

    private static final Logger LOGGER = LoggerFactory.getLogger(FinalizeCommand.class);

    private static final String REPORTER_OVERVIEW_URL = "https://reporter.apache.org/api/overview?sling";
    private static final String REPORTER_COMMITTEE = "sling";

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

    @Override
    public Integer call() {
        try {
            ExecutionMode mode = reusableCLIOptions.executionMode;
            boolean isPmcMember = membersFinder.getCurrentMember().isPMCMember();

            // Resolve the target releases. Before promotion, act by --repository (which still exists);
            // to resume after promotion (which drops the repository), act by --release name instead.
            StagingRepository repository;
            Set<Release> releases;
            if (repositoryId != null) {
                try {
                    repository = repositoryService.find(repositoryId);
                } catch (IllegalArgumentException e) {
                    LOGGER.error(
                            "Staging repository {} was not found — it was most likely already promoted and"
                                    + " dropped. Resume the remaining steps with --release \"<name>\" instead of"
                                    + " --repository.",
                            repositoryId);
                    return CommandLine.ExitCode.USAGE;
                }
                releases = repositoryService.getReleases(repository);
            } else if (releaseName != null && !releaseName.isBlank()) {
                repository = null;
                releases = Set.copyOf(Release.fromString(releaseName));
            } else {
                LOGGER.error("Provide either --repository <id> (initial run) or --release \"<name>\" (to resume after"
                        + " the staging repository has been promoted).");
                return CommandLine.ExitCode.USAGE;
            }

            if (releases.isEmpty()) {
                LOGGER.error("No releases could be resolved.");
                return CommandLine.ExitCode.USAGE;
            }

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
            if (isPmcMember) {
                LOGGER.info("--- Step 1/5: Update dist.apache.org ---");
                if (repository == null) {
                    LOGGER.info("SKIPPED (staging repository already promoted; if dist still needs updating a PMC"
                            + " member must run update-dist separately)");
                } else {
                    stepUpdateDist(repository, mode);
                }
            } else {
                LOGGER.info("--- Step 1/5: Update dist.apache.org --- SKIPPED (current user is not a PMC member;"
                        + " a PMC member must update dist.apache.org separately) ---");
            }

            // Step 2: Promote to Maven Central
            LOGGER.info("--- Step 2/5: Promote to Maven Central ---");
            if (repository == null) {
                LOGGER.info("SKIPPED (staging repository already promoted and dropped)");
            } else if (mode == ExecutionMode.DRY_RUN) {
                LOGGER.info("Would promote {} to Maven Central", repository.getRepositoryId());
            } else {
                LOGGER.info("Promoting {}...", repository.getRepositoryId());
                repositoryService.promote(repository);
                LOGGER.info("Promoted. Artifacts will appear on Maven Central within ~10 minutes.");
            }

            // Step 3: Create next JIRA version and move unresolved issues (idempotent: skips if the successor
            // already exists / there are no unresolved issues left to move)
            LOGGER.info("--- Step 3/5: Create next JIRA version ---");
            for (Release release : releases) {
                stepCreateNextJiraVersion(release, mode);
            }

            // Step 4: Mark JIRA version as released (idempotent: release() skips an already-released version)
            LOGGER.info("--- Step 4/5: Release JIRA version ---");
            for (Release release : releases) {
                stepReleaseJiraVersion(release, stagedAt, mode);
            }

            // Step 5: Update Apache Reporter (idempotent: skips releases the reporter already lists)
            LOGGER.info("--- Step 5/5: Update Apache Reporter ---");
            if (mode == ExecutionMode.DRY_RUN) {
                LOGGER.info("Would add {} release(s) to the Apache Reporter System", releases.size());
                releases.forEach(r -> LOGGER.info("  - {}", r.getFullName()));
            } else {
                stepUpdateReporter(releases);
            }

            LOGGER.info("=== Release finalization complete! ===");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Failed executing command", e);
            return CommandLine.ExitCode.SOFTWARE;
        } catch (Exception e) {
            LOGGER.warn("Failed executing command", e);
            return CommandLine.ExitCode.SOFTWARE;
        }
        return CommandLine.ExitCode.OK;
    }

    private void stepUpdateDist(StagingRepository repository, ExecutionMode mode) throws IOException {
        LocalRepository localRepository = repositoryService.download(repository);
        Artifact primary = localRepository.getArtifacts().stream()
                .filter(a -> "pom".equals(a.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No POM artifact found"));

        String artifactId = primary.getArtifactId();
        String newVersion = primary.getVersion();

        if (UpdateDistCommand.isVersionPublished(artifactId, newVersion)) {
            LOGGER.info("dist/release already contains {} {}; skipping.", artifactId, newVersion);
            return;
        }

        // Publish the staged artifacts (downloaded from Nexus) to dist/release; Maven releases never
        // stage to dist/dev. The previous version to remove is deduced from the current dist/release.
        List<Path> newFiles = UpdateDistCommand.collectDownloadedFiles(localRepository.getRootFolder());
        List<String> oldFiles = UpdateDistCommand.listPreviousReleaseFiles(artifactId, newVersion, null);

        if (newFiles.isEmpty()) {
            LOGGER.warn("No artifacts were downloaded for {} {}; skipping dist update.", artifactId, newVersion);
            return;
        }

        if (mode == ExecutionMode.DRY_RUN) {
            LOGGER.info("Would publish {} file(s) to dist/release for {} {}", newFiles.size(), artifactId, newVersion);
            LOGGER.info("Would remove {} old file(s) from dist/release", oldFiles.size());
        } else {
            Credentials creds = credentialsService.getAsfCredentials();
            UpdateDistCommand.publishToDistRelease(artifactId, newVersion, newFiles, oldFiles, creds);
        }
    }

    private void stepCreateNextJiraVersion(Release release, ExecutionMode mode) throws IOException {
        Version successorVersion = versionClient.findSuccessorVersion(release);
        if (successorVersion == null) {
            Release next = release.next();
            if (mode == ExecutionMode.DRY_RUN) {
                LOGGER.info("Would create JIRA version {}", next.getName());
            } else {
                versionClient.create(next.getName());
                LOGGER.info("Created JIRA version {}", next.getName());
                successorVersion = versionClient.findSuccessorVersion(release);
            }
        } else {
            LOGGER.info("Successor JIRA version {} already exists", successorVersion.getName());
        }
        if (successorVersion != null) {
            List<Issue> unresolved = versionClient.findUnresolvedIssues(release);
            if (!unresolved.isEmpty()) {
                if (mode == ExecutionMode.DRY_RUN) {
                    LOGGER.info(
                            "Would move {} unresolved issue(s) from {} to {}",
                            unresolved.size(),
                            release.getName(),
                            successorVersion.getName());
                } else {
                    versionClient.moveIssuesToNewVersion(versionClient.find(release), successorVersion, unresolved);
                    LOGGER.info("Moved {} unresolved issue(s) to {}", unresolved.size(), successorVersion.getName());
                }
            }
        }
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
            Set<String> alreadyRecorded = fetchRecordedReporterReleases(client);
            Instant now = Instant.now();
            String xdate = DateTimeFormatter.ISO_LOCAL_DATE
                    .withZone(ZoneId.systemDefault())
                    .format(now);
            for (Release release : releases) {
                if (alreadyRecorded != null && alreadyRecorded.contains(release.getFullName())) {
                    LOGGER.info("Apache Reporter already lists {}; skipping.", release.getFullName());
                    continue;
                }
                HttpPost post = new HttpPost("https://reporter.apache.org/addrelease.py");
                List<NameValuePair> params = new ArrayList<>();
                params.add(new BasicNameValuePair("date", Long.toString(now.getEpochSecond())));
                params.add(new BasicNameValuePair("committee", REPORTER_COMMITTEE));
                params.add(new BasicNameValuePair("version", release.getFullName()));
                params.add(new BasicNameValuePair("xdate", xdate));
                post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));
                try (CloseableHttpResponse response = client.execute(post)) {
                    int statusCode = response.getStatusLine().getStatusCode();
                    // addrelease.py returns HTTP 200 even on failure, with the error in the body, so the
                    // status code alone is not enough to tell success from failure.
                    String body = response.getEntity() == null
                            ? ""
                            : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
                                    .strip();
                    if (statusCode == 200 && !body.contains("Could not save")) {
                        LOGGER.info("Updated Apache Reporter for {}", release.getFullName());
                    } else if (body.toLowerCase().contains("access to this committee data")) {
                        // release data is committee-scoped; a non-PMC user cannot add it
                        LOGGER.warn(
                                "Apache Reporter NOT updated for {} — the current user lacks committee access; a PMC"
                                        + " member must add it. Reporter said: {}",
                                release.getFullName(),
                                body);
                    } else {
                        throw new IOException("Reporter update failed for " + release.getFullName() + ": HTTP "
                                + statusCode + (body.isEmpty() ? "" : " - " + body));
                    }
                }
            }
        }
    }

    /**
     * Fetches the set of release names the Apache Reporter already records for the Sling committee, so a
     * resumed run can skip re-adding them. Returns {@code null} if the reporter could not be queried, in
     * which case the caller should post without deduplication rather than risk skipping a real release.
     */
    private Set<String> fetchRecordedReporterReleases(CloseableHttpClient client) {
        HttpGet get = new HttpGet(REPORTER_OVERVIEW_URL);
        get.setHeader("Accept", "application/json");
        try (CloseableHttpResponse response = client.execute(get)) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200 || response.getEntity() == null) {
                LOGGER.warn("Could not query Apache Reporter (HTTP {}); will post without deduplication.", statusCode);
                return null;
            }
            try (InputStreamReader reader =
                    new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8)) {
                JsonObject root = new Gson().fromJson(reader, JsonObject.class);
                JsonObject releases = root == null ? null : root.getAsJsonObject("releases");
                JsonObject committeeReleases = releases == null ? null : releases.getAsJsonObject(REPORTER_COMMITTEE);
                // the reporter keys each release by its full name (e.g. "Apache Sling API 2.27.6")
                return committeeReleases == null ? Set.of() : Set.copyOf(committeeReleases.keySet());
            }
        } catch (Exception e) {
            LOGGER.warn("Could not query Apache Reporter ({}); will post without deduplication.", e.getMessage());
            return null;
        }
    }
}
