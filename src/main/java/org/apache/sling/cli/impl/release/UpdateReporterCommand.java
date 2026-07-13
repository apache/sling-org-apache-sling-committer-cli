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

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

@Component(
        service = Command.class,
        property = {
            Command.PROPERTY_NAME_COMMAND_GROUP + "=" + UpdateReporterCommand.GROUP,
            Command.PROPERTY_NAME_COMMAND_NAME + "=" + UpdateReporterCommand.NAME,
        })
@CommandLine.Command(
        name = UpdateReporterCommand.NAME,
        description = "Updates the Apache Reporter System with the new release information",
        subcommands = CommandLine.HelpCommand.class)
public class UpdateReporterCommand extends AbstractReleaseCommand {

    static final String GROUP = "release";
    static final String NAME = "update-reporter";

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateReporterCommand.class);

    @Reference
    private RepositoryService repositoryService;

    @Reference
    private HttpClientFactory httpClientFactory;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    @Override
    public Integer call() {
        try {
            Set<Release> releases = resolveReleases(repositoryService);
            if (releases.isEmpty()) {
                LOGGER.error("Provide either --repository or --release.");
                return CommandLine.ExitCode.USAGE;
            }
            String releaseReleases = releases.size() > 1 ? "releases" : "release";
            switch (reusableCLIOptions.executionMode) {
                case DRY_RUN:
                    LOGGER.info("The following {} would be added to the Apache Reporter System:", releaseReleases);
                    releases.forEach(release -> LOGGER.info("  - {}", release.getFullName()));
                    break;
                case INTERACTIVE:
                    StringBuilder question = new StringBuilder(String.format(
                                    "Should the following %s be added to the Apache Reporter " + "System?",
                                    releaseReleases))
                            .append("\n");
                    releases.forEach(release -> question.append("  - ")
                            .append(release.getFullName())
                            .append("\n"));
                    InputOption answer = UserInput.yesNo(question.toString(), InputOption.YES);
                    if (InputOption.YES.equals(answer)) {
                        LOGGER.info("Updating the Apache Reporter System...");
                        updateReporter(releases);
                        LOGGER.info("Done.");
                    } else if (InputOption.NO.equals(answer)) {
                        LOGGER.info("Aborted updating the Apache Reporter System.");
                    }
                    break;
                case AUTO:
                    LOGGER.info("The following {} will be added to the Apache Reporter System:", releaseReleases);
                    releases.forEach(release -> LOGGER.info("  - {}", release.getFullName()));
                    updateReporter(releases);
                    LOGGER.info("Done.");
            }

        } catch (IOException e) {
            LOGGER.error("Unable to update the Apache Reporter System.", e);
            return CommandLine.ExitCode.SOFTWARE;
        }
        return CommandLine.ExitCode.OK;
    }

    private void updateReporter(Set<Release> releases) throws IOException {
        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            Set<String> alreadyRecorded = Reporter.fetchRegisteredReleaseNames(client);
            for (Release release : releases) {
                if (alreadyRecorded != null && alreadyRecorded.contains(release.getFullName())) {
                    LOGGER.info("Apache Reporter already lists {}; skipping.", release.getFullName());
                    continue;
                }
                if (Reporter.addRelease(client, release) == Reporter.Result.ACCESS_DENIED) {
                    throw new IOException("The Apache Reporter System update failed for release "
                            + release.getFullName() + ": the current user lacks committee access (release data can"
                            + " only be added by a Sling PMC member or an ASF member).");
                }
                LOGGER.info("Added {} to the Apache Reporter System.", release.getFullName());
            }
        }
    }
}
