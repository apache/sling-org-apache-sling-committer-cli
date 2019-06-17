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

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.nexus.StagingRepositoryFinder;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;

@Component(service = Command.class,
           property = {
                   Command.PROPERTY_NAME_COMMAND_GROUP + "=" + ListCommand.GROUP,
                   Command.PROPERTY_NAME_COMMAND_NAME + "=" + ListCommand.NAME,
           }
)
@CommandLine.Command(name = ListCommand.NAME, description = "Lists all open releases", subcommands = CommandLine.HelpCommand.class)
public class ListCommand implements Command {

    static final String GROUP = "release";
    static final String NAME = "list";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Reference
    private StagingRepositoryFinder repoFinder;

    @Override
    public void run() {
        try {
            repoFinder.list().forEach( r -> logger.info("{}\t{}", r.getRepositoryId(), r.getDescription()));
        } catch (IOException e) {
            logger.warn("Failed executing command", e);
        }
    }

}
