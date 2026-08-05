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
import java.util.List;

import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.jira.Issue;
import org.apache.sling.cli.impl.jira.Version;
import org.apache.sling.cli.impl.jira.VersionClient;
import org.slf4j.Logger;

/**
 * Shared JIRA version housekeeping for the post-vote commands, so {@link CreateJiraVersionCommand} and
 * {@link FinalizeCommand} do not duplicate it: ensure the successor version exists and move any
 * still-unresolved issues from the released version to it.
 */
final class JiraVersions {

    private JiraVersions() {}

    /**
     * Ensures the successor of {@code release} exists (creating it when necessary) and moves the released
     * version's still-unresolved issues to it. Honours the execution {@code mode}: {@code DRY_RUN}
     * describes the actions, {@code INTERACTIVE} confirms each one, {@code AUTO} performs them. Both
     * operations are idempotent, so this is safe to re-run.
     */
    static void createSuccessorAndMoveUnresolved(
            VersionClient versionClient, Release release, ExecutionMode mode, Logger logger) throws IOException {
        Version successorVersion = versionClient.findSuccessorVersion(release);
        if (successorVersion == null) {
            Release next = release.next();
            if (confirm(
                    mode,
                    logger,
                    "Would create JIRA version " + next.getName(),
                    "Should version %s be created?",
                    next.getName())) {
                versionClient.create(next.getName());
                logger.info("Created JIRA version {}", next.getName());
                successorVersion = versionClient.findSuccessorVersion(release);
            }
        } else {
            logger.info("Successor JIRA version {} already exists", successorVersion.getName());
        }
        if (successorVersion != null) {
            List<Issue> unresolved = versionClient.findUnresolvedIssues(release);
            if (!unresolved.isEmpty()
                    && confirm(
                            mode,
                            logger,
                            String.format(
                                    "Would move %d unresolved issue(s) from %s to %s",
                                    unresolved.size(), release.getName(), successorVersion.getName()),
                            "Should the %d unresolved issue(s) from %s be moved to %s?",
                            unresolved.size(),
                            release.getName(),
                            successorVersion.getName())) {
                versionClient.moveIssuesToNewVersion(versionClient.find(release), successorVersion, unresolved);
                logger.info("Moved {} unresolved issue(s) to {}", unresolved.size(), successorVersion.getName());
            }
        }
    }

    /**
     * Decides whether to perform an action for the given mode: in {@code DRY_RUN} logs {@code dryRunMessage}
     * and returns {@code false}; in {@code INTERACTIVE} asks the {@code questionFormat} question; in
     * {@code AUTO} returns {@code true}.
     */
    private static boolean confirm(
            ExecutionMode mode, Logger logger, String dryRunMessage, String questionFormat, Object... questionArgs) {
        switch (mode) {
            case DRY_RUN:
                logger.info(dryRunMessage);
                return false;
            case INTERACTIVE:
                return UserInput.yesNo(String.format(questionFormat, questionArgs), InputOption.YES) == InputOption.YES;
            case AUTO:
            default:
                return true;
        }
    }
}
