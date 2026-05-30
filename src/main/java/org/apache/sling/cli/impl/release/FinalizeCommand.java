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
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.jira.Issue;
import org.apache.sling.cli.impl.jira.Version;
import org.apache.sling.cli.impl.jira.VersionClient;
import org.apache.sling.cli.impl.nexus.Artifact;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.people.MembersFinder;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/**
 * Runs all post-vote release finalization steps in sequence:
 * <ol>
 *   <li>Promote staging repository to Maven Central</li>
 *   <li>Upload artifacts to dist.apache.org (only when the current user is a PMC member)</li>
 *   <li>Create the next JIRA version and move unresolved issues</li>
 *   <li>Mark the current JIRA version as released</li>
 *   <li>Update the Apache Reporter System</li>
 * </ol>
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

    @CommandLine.Option(
            names = {"-r", "--repository"},
            description = "Nexus staging repository id",
            required = true)
    private Integer repositoryId;

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
            StagingRepository repository = repositoryService.find(repositoryId);
            Set<Release> releases = repositoryService.getReleases(repository);
            ExecutionMode mode = reusableCLIOptions.executionMode;
            boolean isPmcMember = membersFinder.getCurrentMember().isPMCMember();

            String releaseNames = releases.stream()
                    .map(Release::getFullName)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(none)");
            LOGGER.info("=== Finalizing: {} ===", releaseNames);

            // Step 1: Promote to Maven Central
            LOGGER.info("--- Step 1/5: Promote to Maven Central ---");
            if (mode == ExecutionMode.DRY_RUN) {
                LOGGER.info("Would promote {} to Maven Central", repository.getRepositoryId());
            } else {
                LOGGER.info("Promoting {}...", repository.getRepositoryId());
                repositoryService.promote(repository);
                LOGGER.info("Promoted. Artifacts will appear on Maven Central within ~10 minutes.");
            }

            // Step 2: Update dist.apache.org (PMC members only; auto-detected)
            if (isPmcMember) {
                LOGGER.info("--- Step 2/5: Update dist.apache.org ---");
                stepUpdateDist(repository, mode);
            } else {
                LOGGER.info("--- Step 2/5: Update dist.apache.org --- SKIPPED (current user is not a PMC member;"
                        + " a PMC member must update dist.apache.org separately) ---");
            }

            // Step 3: Create next JIRA version and move unresolved issues
            LOGGER.info("--- Step 3/5: Create next JIRA version ---");
            for (Release release : releases) {
                stepCreateNextJiraVersion(release, mode);
            }

            // Step 4: Mark JIRA version as released
            LOGGER.info("--- Step 4/5: Release JIRA version ---");
            for (Release release : releases) {
                stepReleaseJiraVersion(release, mode);
            }

            // Step 5: Update Apache Reporter
            LOGGER.info("--- Step 5/5: Update Apache Reporter ---");
            if (mode == ExecutionMode.DRY_RUN) {
                LOGGER.info("Would add {} release(s) to the Apache Reporter System", releases.size());
                releases.forEach(r -> LOGGER.info("  - {}", r.getFullName()));
            } else {
                stepUpdateReporter(releases);
            }

            LOGGER.info("=== Release finalization complete! ===");
        } catch (Exception e) {
            LOGGER.warn("Failed executing command", e);
            return CommandLine.ExitCode.SOFTWARE;
        }
        return CommandLine.ExitCode.OK;
    }

    private void stepUpdateDist(StagingRepository repository, ExecutionMode mode)
            throws IOException, InterruptedException {
        Set<Artifact> artifacts = repositoryService.getArtifacts(repository);
        Artifact primary = artifacts.stream()
                .filter(a -> "pom".equals(a.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No POM artifact found"));

        String artifactId = primary.getArtifactId();
        String newVersion = primary.getVersion();

        List<String> newFiles =
                UpdateDistCommand.listSvnFiles(UpdateDistCommand.DIST_DEV_URL, artifactId + "-" + newVersion);
        // The previous version to remove is deduced from the current dist/release contents.
        List<String> oldFiles = UpdateDistCommand.listPreviousReleaseFiles(artifactId, newVersion, null);

        if (newFiles.isEmpty()) {
            LOGGER.warn("No files found in dist/dev matching {}-{}; skipping dist update.", artifactId, newVersion);
            LOGGER.warn("Run 'release update-dist' manually after staging dist/dev artifacts.");
            return;
        }

        if (mode == ExecutionMode.DRY_RUN) {
            LOGGER.info("Would move {} file(s) from dist/dev to dist/release", newFiles.size());
            newFiles.forEach(f -> LOGGER.info("  mv dev/{} -> release/{}", f, f));
            LOGGER.info("Would remove {} old file(s) from dist/release", oldFiles.size());
            oldFiles.forEach(f -> LOGGER.info("  rm release/{}", f));
        } else {
            Credentials creds = credentialsService.getAsfCredentials();
            UpdateDistCommand.runSvnMucc(artifactId, newVersion, newFiles, oldFiles, creds);
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

    private void stepReleaseJiraVersion(Release release, ExecutionMode mode) throws Exception {
        if (mode == ExecutionMode.DRY_RUN) {
            LOGGER.info("Would mark JIRA version {} as released", release.getFullName());
        } else {
            versionClient.release(release);
            LOGGER.info("Marked JIRA version {} as released", release.getFullName());
        }
    }

    private void stepUpdateReporter(Set<Release> releases) throws IOException {
        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            Date now = new Date();
            for (Release release : releases) {
                HttpPost post = new HttpPost("https://reporter.apache.org/addrelease.py");
                List<NameValuePair> params = new ArrayList<>();
                params.add(new BasicNameValuePair("date", Long.toString(now.getTime() / 1000)));
                params.add(new BasicNameValuePair("committee", "sling"));
                params.add(new BasicNameValuePair("version", release.getFullName()));
                params.add(new BasicNameValuePair("xdate", sdf.format(now)));
                post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));
                try (CloseableHttpResponse response = client.execute(post)) {
                    if (response.getStatusLine().getStatusCode() != 200) {
                        throw new IOException("Reporter update failed for " + release.getFullName() + ": HTTP "
                                + response.getStatusLine().getStatusCode());
                    }
                }
                LOGGER.info("Updated Apache Reporter for {}", release.getFullName());
            }
        }
    }
}
