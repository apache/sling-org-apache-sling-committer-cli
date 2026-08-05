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
public class CloseStagingCommand extends AbstractStagingRepositoryCommand<CloseStagingCommand.Target> {

    static final String GROUP = "release";
    static final String NAME = "close-staging";

    private static final Logger LOGGER = LoggerFactory.getLogger(CloseStagingCommand.class);

    @Reference
    private RepositoryService repositoryService;

    @Override
    protected Target resolve() throws IOException {
        StagingRepository repository = repositoryService.findAny(repositoryId);
        // Derive the description from the staged artifacts' POM (name + version), since releases staged
        // via the plain maven-deploy-plugin only get the generic Nexus "Implicitly created (auto
        // staging)" description. The repository is still open at this point, so it is not in the Lucene
        // index yet; browse its content directly.
        String description = repositoryService.getReleasesFromContent(repository).stream()
                .map(Release::getFullName)
                .collect(Collectors.joining(", "));
        if (description.isEmpty()) {
            description = repository.getDescription();
        }
        return new Target(repository, description);
    }

    @Override
    protected String dryRunMessage(Target target) {
        return String.format(
                "Would close staging repository %s with description \"%s\".",
                target.repository.getRepositoryId(), target.description);
    }

    @Override
    protected String confirmationQuestion(Target target) {
        return String.format(
                "Close staging repository %s with description \"%s\"?",
                target.repository.getRepositoryId(), target.description);
    }

    @Override
    protected void perform(Target target) throws IOException {
        LOGGER.info(
                "Closing staging repository {} with description \"{}\"...",
                target.repository.getRepositoryId(),
                target.description);
        repositoryService.close(target.repository, target.description);
        LOGGER.info(
                "Done. Repository {} is now closed and ready for verification and voting.",
                target.repository.getRepositoryId());
    }

    static final class Target {
        private final StagingRepository repository;
        private final String description;

        Target(StagingRepository repository, String description) {
            this.repository = repository;
            this.description = description;
        }
    }
}
