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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;

@Component(service = Command.class,
           property = {
                   Command.PROPERTY_NAME_COMMAND_GROUP + "=" + UpdateReporterCommand.GROUP,
                   Command.PROPERTY_NAME_COMMAND_NAME + "=" + UpdateReporterCommand.NAME,
           }
)
@CommandLine.Command(
        name = UpdateReporterCommand.NAME,
        description = "Updates the Apache Reporter System with the new release information",
        subcommands = CommandLine.HelpCommand.class
)
public class UpdateReporterCommand implements Command {

    static final String GROUP = "release";
    static final String NAME = "update-reporter";

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateReporterCommand.class);

    @Reference
    private RepositoryService repositoryService;

    @Reference
    private HttpClientFactory httpClientFactory;

    @CommandLine.Option(names = {"-r", "--repository"}, description = "Nexus repository id", required = true)
    private Integer repositoryId;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    @Override
    public Integer call() {
        try {
            StagingRepository repository = repositoryService.find(repositoryId);
            Set<Release> releases = repositoryService.getReleases(repository);
            String releaseReleases = releases.size() > 1 ? "releases" : "release";
            switch (reusableCLIOptions.executionMode) {
                case DRY_RUN:
                    LOGGER.info("The following {} would be added to the Apache Reporter System:", releaseReleases);
                    releases.forEach(release -> LOGGER.info("  - {}", release.getFullName()));
                    break;
                case INTERACTIVE:
                    StringBuilder question = new StringBuilder(String.format("Should the following %s be added to the Apache Reporter " +
                            "System?", releaseReleases)).append("\n");
                    releases.forEach(release -> question.append("  - ").append(release.getFullName()).append("\n"));
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
            LOGGER.error(String.format("Unable to update reporter service; passed command: %s.", repositoryId), e);
            return CommandLine.ExitCode.SOFTWARE;
        }
        return CommandLine.ExitCode.OK;

    }

    private void updateReporter(Set<Release> releases) throws IOException {
        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            for (Release release : releases) {
                HttpPost post = new HttpPost("https://reporter.apache.org/addrelease.py");
                SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");
                List<NameValuePair> parameters = new ArrayList<>();
                Date now = new Date();
                parameters.add(new BasicNameValuePair("date", Long.toString(now.getTime() / 1000)));
                parameters.add(new BasicNameValuePair("committee", "sling"));
                parameters.add(new BasicNameValuePair("version", release.getFullName()));
                parameters.add(new BasicNameValuePair("xdate", simpleDateFormat.format(now)));
                post.setEntity(new UrlEncodedFormEntity(parameters, StandardCharsets.UTF_8));
                try (CloseableHttpResponse response = client.execute(post)) {
                    if (response.getStatusLine().getStatusCode() != 200) {
                        throw new IOException(String.format("The Apache Reporter System update failed for release %s. Got response code " +
                                "%s instead of 200.", release.getFullName(), response.getStatusLine().getStatusCode()));
                    }
                }
            }
        }
    }
}
