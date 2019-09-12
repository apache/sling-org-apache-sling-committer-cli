/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.cli.impl.release;

import java.io.IOException;
import java.util.List;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.jira.Issue;
import org.apache.sling.cli.impl.jira.Version;
import org.apache.sling.cli.impl.jira.VersionClient;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;

@Component(service = Command.class,
           property = {
                   Command.PROPERTY_NAME_COMMAND_GROUP + "=" + CreateJiraVersionCommand.GROUP,
                   Command.PROPERTY_NAME_COMMAND_NAME + "=" + CreateJiraVersionCommand.NAME
           }
)
@CommandLine.Command(
        name = CreateJiraVersionCommand.NAME,
        description = "Creates a new Jira version, if needed, and transitions any unresolved issues from the version being released to " +
                "the next one",
        subcommands = CommandLine.HelpCommand.class
)
public class CreateJiraVersionCommand implements Command {

    static final String GROUP = "release";
    static final String NAME = "create-new-jira-version";

    @CommandLine.Option(names = {"-r", "--repository"}, description = "Nexus repository id", required = true)
    private Integer repositoryId;

    @Reference
    private RepositoryService repoFinder;
    
    @Reference
    private VersionClient versionClient;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public void run() {
        try {
            StagingRepository repo = repoFinder.find(repositoryId);
            for (Release release : Release.fromString(repo.getDescription()) ) {
                Version version = versionClient.find(release);
                logger.info("Found {}.", version);
                Version successorVersion = versionClient.findSuccessorVersion(release);
                boolean createNextRelease = false;
                if ( successorVersion == null ) {
                    Release next = release.next();
                    if (reusableCLIOptions.executionMode == ExecutionMode.DRY_RUN) {
                        logger.info("Version {} would be created.", next.getName());
                    } else if (reusableCLIOptions.executionMode == ExecutionMode.INTERACTIVE) {
                        InputOption answer = UserInput.yesNo(String.format("Should version %s be created?", next.getName()),
                                InputOption.YES);
                        createNextRelease = (answer == InputOption.YES);
                    } else if (reusableCLIOptions.executionMode == ExecutionMode.AUTO) {
                        createNextRelease = true;
                    }
                    if (createNextRelease) {
                        versionClient.create(next.getName());
                        logger.info("Created version {}", next.getName());
                        successorVersion = versionClient.findSuccessorVersion(release);
                    }
                } else {
                    logger.info("Found successor {}.", successorVersion);
                }
                if (successorVersion != null) {
                    List<Issue> unresolvedIssues = versionClient.findUnresolvedIssues(release);
                    if (!unresolvedIssues.isEmpty()) {
                        boolean moveIssues = false;
                        if (reusableCLIOptions.executionMode == ExecutionMode.DRY_RUN) {
                            logger.info("{} unresolved issues would be moved from version {} to version {} :",
                                    unresolvedIssues.size(), version.getName(), successorVersion.getName());
                        } else if (reusableCLIOptions.executionMode == ExecutionMode.INTERACTIVE) {
                            InputOption answer = UserInput.yesNo(String.format("Should the %s unresolved issue(s) from version %s be " +
                                            "moved " +
                                    "to version %s?", unresolvedIssues.size(), version.getName(), successorVersion.getName()),
                                    InputOption.YES);
                            moveIssues = (answer == InputOption.YES);
                        } else if (reusableCLIOptions.executionMode == ExecutionMode.AUTO) {
                            moveIssues = true;
                        }
                        if (moveIssues) {
                            logger.info("Moving the following issues from {} to {}.", version.getName(), successorVersion.getName());
                            unresolvedIssues
                                    .forEach(i -> logger.info("- {} : {}", i.getKey(), i.getSummary()));
                            versionClient.moveIssuesToNewVersion(version, successorVersion, unresolvedIssues);
                            logger.info("Done.");
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed executing command", e);
        }
    }
}
