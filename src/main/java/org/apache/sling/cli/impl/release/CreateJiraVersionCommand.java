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
import org.apache.sling.cli.impl.ExecutionContext;
import org.apache.sling.cli.impl.jira.Issue;
import org.apache.sling.cli.impl.jira.Version;
import org.apache.sling.cli.impl.jira.VersionClient;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.nexus.StagingRepositoryFinder;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(service = Command.class, property = {
        Command.PROPERTY_NAME_COMMAND+"=release",
        Command.PROPERTY_NAME_SUBCOMMAND+"=create-jira-new-version",
        Command.PROPERTY_NAME_SUMMARY+"=Creates a new Jira version, if needed, and transitions any unresolved issues from the version being released to the next one."
    })
public class CreateJiraVersionCommand implements Command {

    @Reference
    private StagingRepositoryFinder repoFinder;
    
    @Reference
    private VersionClient versionClient;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public void execute(ExecutionContext context) {
        try {
            StagingRepository repo = repoFinder.find(Integer.parseInt(context.getTarget()));
            for (Release release : Release.fromString(repo.getDescription()) ) {
                Version version = versionClient.find(release);
                logger.info("Found version {}", version);
                Version successorVersion = versionClient.findSuccessorVersion(release);
                logger.info("Found successor version {}", successorVersion);
                if ( successorVersion == null ) {
                    Release next = release.next();
                    logger.info("Would create version {}", next.getName());
                    versionClient.create(next.getName());
                    logger.info("Created version {}", next.getName());
                    successorVersion = versionClient.findSuccessorVersion(release);
                }
                
                List<Issue> unresolvedIssues = versionClient.findUnresolvedIssues(release);
                if ( !unresolvedIssues.isEmpty() ) {
                    logger.info("Will move {} unresolved issues from version {} to version {} :", 
                            unresolvedIssues.isEmpty(), version.getName(), successorVersion.getName());
                    unresolvedIssues.stream()
                        .forEach( i -> logger.info("- {} : {}", i.getKey(), i.getSummary()));
                    versionClient.moveIssuesToNewVersion(version, successorVersion, unresolvedIssues);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed executing command", e);
        }
    }
}
