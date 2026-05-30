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
            Command.PROPERTY_NAME_COMMAND_GROUP + "=" + CloseStagingCommand.GROUP,
            Command.PROPERTY_NAME_COMMAND_NAME + "=" + CloseStagingCommand.NAME
        })
@CommandLine.Command(
        name = CloseStagingCommand.NAME,
        description =
                "Closes an open Nexus staging repository after 'mvn release:perform', making it ready for verification and voting",
        subcommands = CommandLine.HelpCommand.class)
public class CloseStagingCommand implements Command {

    static final String GROUP = "release";
    static final String NAME = "close-staging";

    private static final Logger LOGGER = LoggerFactory.getLogger(CloseStagingCommand.class);

    @CommandLine.Option(
            names = {"-r", "--repository"},
            description = "Nexus staging repository id (numeric part, e.g. 1087)",
            required = true)
    private Integer repositoryId;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    @Reference
    private RepositoryService repositoryService;

    @Override
    public Integer call() {
        try {
            StagingRepository repository = repositoryService.findAny(repositoryId);
            // Derive the description from the staged artifacts' POM (name + version), since
            // releases staged via the plain maven-deploy-plugin only get the generic Nexus
            // "Implicitly created (auto staging)" description. The repository is still open at
            // this point, so it is not in the Lucene index yet; browse its content directly.
            String description = repositoryService.getReleasesFromContent(repository).stream()
                    .map(Release::getFullName)
                    .collect(Collectors.joining(", "));
            if (description.isEmpty()) {
                description = repository.getDescription();
            }
            switch (reusableCLIOptions.executionMode) {
                case DRY_RUN:
                    LOGGER.info(
                            "Would close staging repository {} with description \"{}\".",
                            repository.getRepositoryId(),
                            description);
                    break;
                case INTERACTIVE:
                    InputOption answer = UserInput.yesNo(
                            String.format(
                                    "Close staging repository %s with description \"%s\"?",
                                    repository.getRepositoryId(), description),
                            InputOption.YES);
                    if (InputOption.YES.equals(answer)) {
                        doClose(repository, description);
                    } else {
                        LOGGER.info("Aborted.");
                    }
                    break;
                case AUTO:
                    doClose(repository, description);
                    break;
            }
        } catch (IOException e) {
            LOGGER.warn("Failed executing command", e);
            return CommandLine.ExitCode.SOFTWARE;
        }
        return CommandLine.ExitCode.OK;
    }

    private void doClose(StagingRepository repository, String description) throws IOException {
        LOGGER.info(
                "Closing staging repository {} with description \"{}\"...", repository.getRepositoryId(), description);
        repositoryService.close(repository, description);
        LOGGER.info(
                "Done. Repository {} is now closed and ready for verification and voting.",
                repository.getRepositoryId());
    }
}
