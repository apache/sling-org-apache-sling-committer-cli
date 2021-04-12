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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Set;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.jbake.JBakeContentUpdater;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;

@Component(service = Command.class,
           property = {
                   Command.PROPERTY_NAME_COMMAND_GROUP + "=" + UpdateLocalSiteCommand.GROUP,
                   Command.PROPERTY_NAME_COMMAND_NAME + "=" + UpdateLocalSiteCommand.NAME
           }
)
@CommandLine.Command(name = UpdateLocalSiteCommand.NAME, description = "Updates the Sling website with the new release information, " +
        "based on a local checkout", subcommands = CommandLine.HelpCommand.class)
public class UpdateLocalSiteCommand implements Command {

    static final String GROUP = "release";
    static final String NAME = "update-local-site";

    private static final String GIT_CHECKOUT = "/tmp/sling-site";

    @Reference
    private RepositoryService repositoryService;
    
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @CommandLine.Option(names = {"-r", "--repository"}, description = "Nexus repository id", required = true)
    private Integer repositoryId;

    @Override
    public Integer call() throws Exception {
        try {
            ensureRepo();
            try ( Git git = Git.open(new File(GIT_CHECKOUT)) ) {
                
                StagingRepository repository = repositoryService.find(repositoryId);
                Set<Release> releases = repositoryService.getReleases(repository);
                
                JBakeContentUpdater updater = new JBakeContentUpdater();
        
                Path templatePath = Paths.get(GIT_CHECKOUT, "src", "main", "jbake", "templates", "downloads.tpl");
                Path releasesPath = Paths.get(GIT_CHECKOUT, "src", "main", "jbake", "content", "releases.md");
                LocalDateTime now = LocalDateTime.now();
                for ( Release release : releases ) {
                    updater.updateDownloads(templatePath, release.getComponent(), release.getVersion());
                    updater.updateReleases(releasesPath, release.getComponent(), release.getVersion(), now);
                }
        
                git.diff()
                    .setOutputStream(System.out)
                    .call();
            }
        } catch (GitAPIException | IOException e) {
            logger.warn("Failed executing command", e);
            return 1;
        }
        return 0;
            
    }

    private void ensureRepo() throws GitAPIException, IOException {
        
        if ( !Paths.get(GIT_CHECKOUT).toFile().exists() ) {
            Git.cloneRepository()
            .setURI("https://github.com/apache/sling-site.git")
            .setProgressMonitor(new TextProgressMonitor())
            .setDirectory(new File(GIT_CHECKOUT))
            .call();
        } else {
            try ( Git git = Git.open(new File(GIT_CHECKOUT)) )  {
                git.reset()
                    .setMode(ResetType.HARD)
                    .call();
            }
        }
    }
}
