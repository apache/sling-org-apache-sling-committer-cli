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
import org.apache.sling.cli.impl.UserInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/**
 * Base for the staging-repository commands (close-staging, promote, drop). They all take a repository
 * id, resolve a target from it and then act on that target according to the CLI execution mode. This
 * class owns the shared CLI options and the execution-mode dispatch, leaving each command to declare
 * its own {@code RepositoryService} reference (kept in the concrete class so Declarative Services
 * generates the component descriptor) and to provide its command-specific behaviour.
 *
 * @param <T> the resolved target the command operates on (a repository, or a small context holding the
 *            repository together with derived display information)
 */
abstract class AbstractStagingRepositoryCommand<T> implements Command {

    @CommandLine.Option(
            names = {"-r", "--repository"},
            description = "Nexus staging repository id",
            required = true)
    protected Integer repositoryId;

    @CommandLine.Mixin
    protected ReusableCLIOptions reusableCLIOptions;

    @Override
    public final Integer call() {
        Logger logger = LoggerFactory.getLogger(getClass());
        try {
            T target = resolve();
            switch (reusableCLIOptions.executionMode) {
                case DRY_RUN:
                    logger.info(dryRunMessage(target));
                    break;
                case INTERACTIVE:
                    if (InputOption.YES.equals(UserInput.yesNo(confirmationQuestion(target), interactiveDefault()))) {
                        perform(target);
                    } else {
                        logger.info("Aborted.");
                    }
                    break;
                case AUTO:
                    perform(target);
                    break;
            }
        } catch (IOException e) {
            logger.warn("Failed executing command", e);
            return CommandLine.ExitCode.SOFTWARE;
        }
        return CommandLine.ExitCode.OK;
    }

    /** Resolves the target this command acts on from {@link #repositoryId}. */
    protected abstract T resolve() throws IOException;

    /** The message logged in dry-run mode. */
    protected abstract String dryRunMessage(T target);

    /** The yes/no question asked in interactive mode. */
    protected abstract String confirmationQuestion(T target);

    /** Performs the actual state-changing operation. */
    protected abstract void perform(T target) throws IOException;

    /** The pre-selected option for the interactive confirmation; defaults to {@link InputOption#YES}. */
    protected InputOption interactiveDefault() {
        return InputOption.YES;
    }
}
