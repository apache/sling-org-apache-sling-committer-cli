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

import static org.osgi.service.component.annotations.ReferenceCardinality.MULTIPLE;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sling.cli.impl.CommandProcessor.Config;
import org.apache.sling.cli.impl.release.ReleaseCLIGroup;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;

@CommandLine.Command(
        name = "docker run -it --env-file=./docker-env apache/sling-cli",
        description = "Apache Sling Committers CLI"
)
@Designate(ocd = Config.class)
@Component(service = CommandProcessor.class)
public class CommandProcessor {

    @ObjectClassDefinition
    @interface Config {
        @AttributeDefinition
        String cliSpec() default "";
    }

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private BundleContext ctx;
    private Config cfg;

    private static final Map<String, Class> CLI_GROUPS;

    static {
        CLI_GROUPS = new HashMap<>();
        CLI_GROUPS.put("release", ReleaseCLIGroup.class);
    }

    private Map<String, TreeSet<CommandWithProps>> commands = new ConcurrentHashMap<>();

    @Activate
    private void activate(BundleContext ctx, Config cfg) {
        this.ctx = ctx;
        this.cfg = cfg;
    }

    @Reference(service = Command.class, cardinality = MULTIPLE, policy = DYNAMIC)
    protected void bindCommand(Command cmd, Map<String, ?> props) {
        CommandWithProps commandWithProps = CommandWithProps.of(cmd, props);
        Set<CommandWithProps> bucket = commands.computeIfAbsent(commandWithProps.group, key -> new TreeSet<>());
        bucket.add(commandWithProps);
    }

    protected void unbindCommand(Command cmd, Map<String, ?> props) {
        CommandWithProps commandWithProps = CommandWithProps.of(cmd, props);
        Set<CommandWithProps> bucket = commands.get(commandWithProps.group);
        if (bucket != null) {
            bucket.remove(commandWithProps);
            if (bucket.isEmpty()) {
                commands.remove(commandWithProps.group);
            }
        }

    }

    void runCommand() {
        System.setProperty("picocli.usage.width", "140");
        CommandLine commandLine = new CommandLine(this);
        commandLine.addSubcommand(CommandLine.HelpCommand.class);
        for (Map.Entry<String, TreeSet<CommandWithProps>> entry : commands.entrySet()) {
            String group = entry.getKey();
            Class<?> groupClass = CLI_GROUPS.get(group);
            if (groupClass != null) {
                CommandLine secondary = new CommandLine(groupClass);
                for (CommandWithProps command : entry.getValue()) {
                    secondary.addSubcommand(command.name, command.cmd);
                }
                secondary.addSubcommand(CommandLine.HelpCommand.class);
                commandLine.addSubcommand(group, secondary);
            } else {
                for (CommandWithProps command : entry.getValue()) {
                    commandLine.addSubcommand(command.group, command.cmd);
                }
            }
        }
        int commandExitCode;
        try {
            String[] arguments = getArgLine().split("\\n");
            commandExitCode = commandLine.execute(arguments);
        } catch (CommandLine.ParameterException e) {
            commandLine.getErr().println(e.getMessage());
            if (!CommandLine.UnmatchedArgumentException.printSuggestions(e, commandLine.getErr())) {
                e.getCommandLine().usage(commandLine.getErr());
            }
            commandExitCode = commandLine.getCommandSpec().exitCodeOnInvalidInput();
        } catch (Exception e) {
            logger.warn("Failed running command.", e);
            commandExitCode = 1;
        } finally {
            stopFramework();
        }
        terminateExecution(commandExitCode);
    }

    // visible for testing
    protected String getArgLine() {
        return cfg.cliSpec();
    }

    // visible for testing
    protected void terminateExecution(int commandExitCode) {
        System.exit(commandExitCode);
    }

    // visible for testing
    protected void stopFramework() {
        try {
            ctx.getBundle(Constants.SYSTEM_BUNDLE_LOCATION).adapt(Framework.class).stop();
        } catch (BundleException e) {
            logger.error("Failed shutting down framework, forcing exit", e);
            System.exit(1);
        }
    }

    static class CommandWithProps implements Comparable<CommandWithProps> {
        private final String group;
        private final String name;
        private final Command cmd;

        static CommandWithProps of(Command cmd, Map<String, ?> props) {
            return new CommandWithProps(
                    cmd,
                    (String) props.get(Command.PROPERTY_NAME_COMMAND_GROUP),
                    (String) props.get(Command.PROPERTY_NAME_COMMAND_NAME)
            );
        }

        CommandWithProps(Command cmd, String group, String name) {
            this.cmd = cmd;
            this.group = group;
            this.name = name;
        }

        @Override
        public int compareTo(@NotNull CommandProcessor.CommandWithProps o) {
            if (!group.equals(o.group)) {
                return group.compareTo(o.group);
            } else {
                return name.compareTo(o.name);
            }
        }

        @Override
        public int hashCode() {
            return Objects.hash(group, name);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (obj instanceof CommandWithProps) {
                CommandWithProps other = (CommandWithProps) obj;
                return Objects.equals(group, other.group) && Objects.equals(name, other.name);
            }
            return false;
        }
    }
}
