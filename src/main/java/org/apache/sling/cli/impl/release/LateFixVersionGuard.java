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

import org.apache.sling.cli.impl.jira.Issue;
import org.apache.sling.cli.impl.jira.VersionClient;
import org.slf4j.Logger;

/**
 * Shared logic for the post-vote commands that close a JIRA version's fixed issues. Detects issues
 * that acquired the release's fix version only <em>after</em> the release artifacts were staged &mdash;
 * such issues cannot be part of the release and closing them under it is wrong (see SLING-13260).
 */
final class LateFixVersionGuard {

    private LateFixVersionGuard() {}

    /**
     * Finds issues tagged with the release's fix version after {@code stagedAt} and logs them, phrasing
     * the message according to whether the operator chose to override the guard.
     *
     * @param versionClient the JIRA client
     * @param release the release being closed
     * @param stagedAt the moment the artifacts were staged, or {@code null} when it is unknown (e.g. the
     *     staging repository no longer exists), in which case the check is skipped
     * @param force whether the operator passed {@code --force-close-late-issues}
     * @param logger the caller's logger, so messages are attributed to the running command
     * @return the offending issues, never {@code null}
     * @throws IOException in case of any errors talking to JIRA
     */
    static List<Issue> reportLateIssues(
            VersionClient versionClient, Release release, Instant stagedAt, boolean force, Logger logger)
            throws IOException {
        List<Issue> late = stagedAt == null ? List.of() : versionClient.findIssuesFixVersionedAfter(release, stagedAt);
        if (late.isEmpty()) {
            return late;
        }
        if (force) {
            logger.warn(
                    "{} issue(s) acquired fix version \"{}\" after the artifacts were staged ({}); closing them"
                            + " anyway because --force-close-late-issues was given:",
                    late.size(),
                    release.getName(),
                    stagedAt);
        } else {
            logger.warn(
                    "Refusing to release JIRA version {}: the following issue(s) acquired this fix version after the"
                            + " artifacts were staged ({}) and cannot be part of the release. Re-tag them to the"
                            + " correct fix version, or pass --force-close-late-issues to close them anyway:",
                    release.getFullName(),
                    stagedAt);
        }
        late.forEach(issue -> logger.warn("  - {} ({})", issue.getKey(), issue.getSummary()));
        return late;
    }
}
