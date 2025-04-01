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
package org.apache.sling.cli.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.Map;

import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Rule;
import org.junit.Test;

import picocli.CommandLine;

public class CommandProcessorTest {

    @CommandLine.Command(
            name = MockCommand.COMMAND_NAME,
            description = "Mock Command"
    )
    public class MockCommand implements Command {

        static final String COMMAND_GROUP = "release"; // match existing command group to simplify invocation
        static final String COMMAND_NAME = "mock";

        @CommandLine.Option(names = {"-r", "--repository"}, description = "Nexus repository id", required = true)
        private Integer repositoryId;

        @CommandLine.Option(names = {"-d", "--description"}, description = "Long form description", required = true)
        private String description;

        private int invocationCount;

        @Override
        public Integer call() throws Exception {
            System.out.println("Invoked mock command");
            invocationCount++;
            return 0;
        }
    }

    @Rule
    public final OsgiContext context = new OsgiContext();

    /**
     * Validates that a variety of arguments are parsed correctly
     */
    @Test
    public void argumentParsing() {

        // this argline was genreated by inspecting the result of running src/main/resources/scripts/run.sh
        CommandProcessor commandProcessor = new CommandProcessor() {
            @Override
            protected String getArgLine() {
                return "release\n"
                        + "mock\n"
                        + "-r\n"
                        + "24\n"
                        + "--description=Test 'description'";
            }
            @Override
            protected void stopFramework() { /* ignored  */}
            @Override
            protected void terminateExecution(int commandExitCode) { /* ignored  */ }
        };
        MockCommand cmd = new MockCommand();
        commandProcessor.bindCommand(cmd, Map.of(Command.PROPERTY_NAME_COMMAND_GROUP, MockCommand.COMMAND_GROUP, Command.PROPERTY_NAME_COMMAND_NAME, MockCommand.COMMAND_NAME));

        commandProcessor.runCommand();

        assertThat(cmd.invocationCount, equalTo(1));
        assertThat(cmd.repositoryId, equalTo(24));
        assertThat(cmd.description, equalTo("Test 'description'"));
    }
}
