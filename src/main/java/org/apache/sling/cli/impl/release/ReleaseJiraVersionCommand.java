/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ~ Licensed to the Apache Software Foundation (ASF) under one
 ~ or more contributor license agreements.  See the NOTICE file
 ~ distributed with this work for additional information
 ~ regarding copyright ownership.  The ASF licenses this file
 ~ to you under the Apache License, Version 2.0 (the
 ~ "License"); you may not use this file except in compliance
 ~ with the License.  You may obtain a copy of the License at
 ~
 ~   http://www.apache.org/licenses/LICENSE-2.0
 ~
 ~ Unless required by applicable law or agreed to in writing,
 ~ software distributed under the License is distributed on an
 ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 ~ KIND, either express or implied.  See the License for the
 ~ specific language governing permissions and limitations
 ~ under the License.
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/
package org.apache.sling.cli.impl.release;

import java.util.List;
import java.util.Set;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.jira.Issue;
import org.apache.sling.cli.impl.jira.VersionClient;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;

@Component(service = Command.class,
           property = {
                   Command.PROPERTY_NAME_COMMAND_GROUP + "=" + ReleaseJiraVersionCommand.GROUP,
                   Command.PROPERTY_NAME_COMMAND_NAME + "=" + ReleaseJiraVersionCommand.NAME
           }
)
@CommandLine.Command(
        name = ReleaseJiraVersionCommand.NAME,
        description = "The found Jira versions will be marked as released with the current date. All fixed issues will be closed. Before " +
                "running this command make sure to execute " + CreateJiraVersionCommand.NAME + " in order to move any unresolved issues " +
                "to the next version.",
        subcommands = CommandLine.HelpCommand.class
)
public class ReleaseJiraVersionCommand implements Command {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReleaseJiraVersionCommand.class);

    static final String GROUP = "release";
    static final String NAME = "release-jira-version";

    @CommandLine.Option(names = {"-r", "--repository"}, description = "Nexus repository id", required = true)
    private Integer repositoryId;

    @Reference
    private RepositoryService repositoryService;

    @Reference
    private VersionClient versionClient;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    @Override
    public Integer call() throws Exception {
        try {
            StagingRepository repo = repositoryService.find(repositoryId);
            Set<Release> releases = repositoryService.getReleases(repo);
            ExecutionMode executionMode = reusableCLIOptions.executionMode;
            LOGGER.info("The following Jira versions {} be released:{}", executionMode == ExecutionMode.DRY_RUN ? "would" : "will",
                    System.lineSeparator());
            for (Release release : releases) {
                List<Issue> fixedIssues = versionClient.findFixedIssues(release);
                LOGGER.info("{}:", release.getFullName());
                fixedIssues.forEach(issue -> LOGGER.info("- {} - {}, Status: {}, Resolution: {}", issue.getKey(), issue.getSummary(),
                        issue.getStatus(), issue.getResolution()));
                LOGGER.info("");
                boolean shouldRelease = false;
                if (executionMode == ExecutionMode.INTERACTIVE) {
                    InputOption answer = UserInput.yesNo(String.format("Should version %s be released?", release.getFullName()),
                            InputOption.YES);
                    shouldRelease = (answer == InputOption.YES);
                } else if (executionMode == ExecutionMode.AUTO) {
                    shouldRelease = true;
                }
                if (shouldRelease) {
                    versionClient.release(release);
                    LOGGER.info("{} was released:", release.getFullName());
                    fixedIssues = versionClient.findFixedIssues(release);
                    fixedIssues.forEach(issue -> LOGGER.info("- {} - {}, Status: {}, Resolution: {}", issue.getKey(), issue.getSummary(),
                            issue.getStatus(), issue.getResolution()));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed executing command.", e);
            return 1;
        }
        return 0;
    }
}
