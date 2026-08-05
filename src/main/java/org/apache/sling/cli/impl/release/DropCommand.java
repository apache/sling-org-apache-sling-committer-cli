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

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.InputOption;
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
            Command.PROPERTY_NAME_COMMAND_GROUP + "=" + DropCommand.GROUP,
            Command.PROPERTY_NAME_COMMAND_NAME + "=" + DropCommand.NAME
        })
@CommandLine.Command(
        name = DropCommand.NAME,
        description = "Drops a Nexus staging repository. Use when a vote fails or to clean up a failed release.",
        subcommands = CommandLine.HelpCommand.class)
public class DropCommand extends AbstractStagingRepositoryCommand<StagingRepository> {

    static final String GROUP = "release";
    static final String NAME = "drop";

    private static final Logger LOGGER = LoggerFactory.getLogger(DropCommand.class);

    @Reference
    private RepositoryService repositoryService;

    @Override
    protected StagingRepository resolve() throws IOException {
        return repositoryService.findAny(repositoryId);
    }

    @Override
    protected String dryRunMessage(StagingRepository repository) {
        return String.format(
                "Would drop staging repository %s: %s", repository.getRepositoryId(), repository.getDescription());
    }

    @Override
    protected String confirmationQuestion(StagingRepository repository) {
        return String.format(
                "Drop staging repository %s (%s)? This cannot be undone.",
                repository.getRepositoryId(), repository.getDescription());
    }

    @Override
    protected InputOption interactiveDefault() {
        return InputOption.NO;
    }

    @Override
    protected void perform(StagingRepository repository) throws IOException {
        LOGGER.info("Dropping staging repository {}...", repository.getRepositoryId());
        repositoryService.drop(repository);
        LOGGER.info("Done. Repository {} has been dropped.", repository.getRepositoryId());
    }
}
