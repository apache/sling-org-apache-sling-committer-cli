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
import java.util.Collection;
import java.util.List;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.jira.VersionClient;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

@Component(
        service = Command.class,
        property = {
            Command.PROPERTY_NAME_COMMAND_GROUP + "=" + CreateJiraVersionCommand.GROUP,
            Command.PROPERTY_NAME_COMMAND_NAME + "=" + CreateJiraVersionCommand.NAME
        })
@CommandLine.Command(
        name = CreateJiraVersionCommand.NAME,
        description =
                "Creates a new Jira version, if needed, and transitions any unresolved issues from the version being released to "
                        + "the next one",
        subcommands = CommandLine.HelpCommand.class)
public class CreateJiraVersionCommand implements Command {

    static final String GROUP = "release";
    static final String NAME = "create-new-jira-version";

    @CommandLine.Option(
            names = {"-r", "--repository"},
            description = "Nexus staging repository id to derive the release(s) from")
    private Integer repositoryId;

    @Reference
    private RepositoryService repositoryService;

    @Reference
    private VersionClient versionClient;

    @CommandLine.Option(
            names = {"--release", "--version-name"},
            description = "Release name(s) to act on, e.g. \"Apache Sling Foo 1.2.0\" (comma-separated for multiple)."
                    + " Use instead of --repository when the staging repository no longer exists,"
                    + " e.g. after the release has been promoted.")
    private String jiraVersionName;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public Integer call() {
        try {
            Collection<Release> releases = releases();
            if (releases.isEmpty()) {
                logger.error("Provide either --repository or --release.");
                return CommandLine.ExitCode.USAGE;
            }
            for (Release release : releases) {
                logger.info("Found {}.", versionClient.find(release));
                JiraVersions.createSuccessorAndMoveUnresolved(
                        versionClient, release, reusableCLIOptions.executionMode, logger);
            }
        } catch (IOException e) {
            logger.warn("Failed executing command", e);
            return CommandLine.ExitCode.SOFTWARE;
        }
        return CommandLine.ExitCode.OK;
    }

    private Collection<Release> releases() throws IOException {
        if (jiraVersionName != null && !jiraVersionName.isBlank()) {
            return Release.fromString(jiraVersionName);
        }
        if (repositoryId == null) {
            return List.of();
        }
        return repositoryService.getReleases(repositoryService.find(repositoryId));
    }
}
