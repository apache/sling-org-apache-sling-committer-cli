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
public class PromoteCommand extends AbstractStagingRepositoryCommand<PromoteCommand.Target> {

    static final String GROUP = "release";
    static final String NAME = "promote";

    private static final Logger LOGGER = LoggerFactory.getLogger(PromoteCommand.class);

    @Reference
    private RepositoryService repositoryService;

    @Override
    protected Target resolve() throws IOException {
        StagingRepository repository = repositoryService.find(repositoryId);
        Set<Release> releases = repositoryService.getReleases(repository);
        String releaseNames = releases.stream().map(Release::getFullName).collect(Collectors.joining(", "));
        return new Target(repository, releaseNames);
    }

    @Override
    protected String dryRunMessage(Target target) {
        return String.format(
                "Would promote %s to Maven Central from repository %s",
                target.releaseNames, target.repository.getRepositoryId());
    }

    @Override
    protected String confirmationQuestion(Target target) {
        return String.format("Promote %s to Maven Central?", target.releaseNames);
    }

    @Override
    protected void perform(Target target) throws IOException {
        LOGGER.info("Promoting {} to Maven Central...", target.releaseNames);
        repositoryService.promote(target.repository);
        LOGGER.info("Done. Artifacts will appear on Maven Central within ~10 minutes.");
    }

    static final class Target {
        private final StagingRepository repository;
        private final String releaseNames;

        Target(StagingRepository repository, String releaseNames) {
            this.repository = repository;
            this.releaseNames = releaseNames;
        }
    }
}
