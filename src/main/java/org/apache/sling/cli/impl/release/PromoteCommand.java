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
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

@Component(
        service = Command.class,
        property = {
            Command.PROPERTY_NAME_COMMAND_GROUP + "=" + PromoteCommand.GROUP,
            Command.PROPERTY_NAME_COMMAND_NAME + "=" + PromoteCommand.NAME
        })
@CommandLine.Command(
        name = PromoteCommand.NAME,
        description = "Promotes a closed Nexus staging repository to Maven Central after a successful vote",
        subcommands = CommandLine.HelpCommand.class)
public class PromoteCommand implements Command {

    static final String GROUP = "release";
    static final String NAME = "promote";

    private static final Logger LOGGER = LoggerFactory.getLogger(PromoteCommand.class);

    @CommandLine.Option(
            names = {"-r", "--repository"},
            description = "Nexus staging repository id",
            required = true)
    private Integer repositoryId;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    @Reference
    private RepositoryService repositoryService;

    @Override
    public Integer call() {
        try {
            StagingRepository repository = repositoryService.find(repositoryId);
            Set<Release> releases = repositoryService.getReleases(repository);
            String releaseNames = releases.stream().map(Release::getFullName).collect(Collectors.joining(", "));
            switch (reusableCLIOptions.executionMode) {
                case DRY_RUN:
                    LOGGER.info(
                            "Would promote {} to Maven Central from repository {}",
                            releaseNames,
                            repository.getRepositoryId());
                    break;
                case INTERACTIVE:
                    InputOption answer = UserInput.yesNo(
                            String.format("Promote %s to Maven Central?", releaseNames), InputOption.YES);
                    if (InputOption.YES.equals(answer)) {
                        doPromote(repository, releaseNames);
                    } else {
                        LOGGER.info("Aborted.");
                    }
                    break;
                case AUTO:
                    doPromote(repository, releaseNames);
                    break;
            }
        } catch (IOException e) {
            LOGGER.warn("Failed executing command", e);
            return CommandLine.ExitCode.SOFTWARE;
        }
        return CommandLine.ExitCode.OK;
    }

    private void doPromote(StagingRepository repository, String releaseNames) throws IOException {
        LOGGER.info("Promoting {} to Maven Central...", releaseNames);
        repositoryService.promote(repository);
        LOGGER.info("Done. Artifacts will appear on Maven Central within ~10 minutes.");
    }
}
