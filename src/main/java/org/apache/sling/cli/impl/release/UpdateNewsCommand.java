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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.jbake.JBakeContentUpdater;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.people.MembersFinder;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/**
 * Announces a release on the Sling website's news page.
 *
 * <p>Deliberately <em>not</em> part of {@link FinalizeCommand}: the release management guide only asks for a
 * news entry when a release warrants an announcement, which is a judgement call, and most module releases do
 * not get one. Run this by hand for the releases that do.
 */
@Component(
        service = Command.class,
        property = {
            Command.PROPERTY_NAME_COMMAND_GROUP + "=" + UpdateNewsCommand.GROUP,
            Command.PROPERTY_NAME_COMMAND_NAME + "=" + UpdateNewsCommand.NAME
        })
@CommandLine.Command(
        name = UpdateNewsCommand.NAME,
        description = "Announces a release on the Sling website's news page. Run only for releases worth announcing;"
                + " this is not part of finalize.",
        subcommands = CommandLine.HelpCommand.class)
public class UpdateNewsCommand extends AbstractReleaseCommand {

    static final String GROUP = "release";
    static final String NAME = "update-news";

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateNewsCommand.class);

    @CommandLine.Option(
            names = {"--link"},
            description = "Optional page the announcement should link to, e.g."
                    + " /news/sling-14-released.html or /documentation/bundles/sling-pipes.html")
    private String link;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    @Reference
    private RepositoryService repositoryService;

    @Reference
    private CredentialsService credentialsService;

    @Reference
    private MembersFinder membersFinder;

    @Override
    public Integer call() {
        try {
            Set<Release> releases = resolveReleases(repositoryService);
            if (releases.isEmpty()) {
                LOGGER.error("Provide either --repository or --release.");
                return CommandLine.ExitCode.USAGE;
            }

            UpdateLocalSiteCommand.ensureRepo();
            Path newsPath =
                    Paths.get(UpdateLocalSiteCommand.checkoutDir(), "src", "main", "jbake", "content", "news.md");

            JBakeContentUpdater updater = new JBakeContentUpdater();
            LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
            boolean changed = false;
            for (Release release : releases) {
                if (updater.updateNews(newsPath, release.getFullName(), link, now)) {
                    LOGGER.info("Added a news entry for {}", release.getFullName());
                    changed = true;
                } else {
                    LOGGER.info("The news page already announces {}; skipping.", release.getFullName());
                }
            }

            if (!changed) {
                LOGGER.info("Nothing to commit.");
                return CommandLine.ExitCode.OK;
            }

            UpdateLocalSiteCommand.printDiff();

            String names = releases.stream().map(Release::getFullName).sorted().collect(Collectors.joining(", "));
            UpdateLocalSiteCommand.commitAndPushSiteChanges(
                    "Announce " + names,
                    "Commit the news entry above and push to sling-site?",
                    reusableCLIOptions.executionMode,
                    credentialsService.getAsfCredentials(),
                    membersFinder.getCurrentMember());
        } catch (GitAPIException | IOException e) {
            LOGGER.warn("Failed executing command", e);
            return CommandLine.ExitCode.SOFTWARE;
        }
        return CommandLine.ExitCode.OK;
    }
}
