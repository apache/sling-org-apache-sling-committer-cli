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
package org.apache.sling.cli.impl;

import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defines the way a {@link Command} will be executed, together with the {@code command}'s execution target.
 */
public class ExecutionContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionContext.class);

    private final Mode mode;
    private final String target;
    public static final ExecutionContext DEFAULT = new ExecutionContext(Mode.DRY_RUN, null);

    /**
     * Creates an {@code ExecutionContext}.
     *
     * @param target the command's target
     * @param mode   the execution mode
     */
    public ExecutionContext(@NotNull Mode mode, @Nullable String target) {
        this.mode = mode;
        this.target = Objects.requireNonNullElse(target, "");
    }

    /**
     * Returns the execution target for a command.
     *
     * @return the execution target
     */
    @NotNull
    public String getTarget() {
        return target;
    }

    /**
     * Returns the execution mode for a command.
     *
     * @return the execution mode
     */
    @NotNull
    public Mode getMode() {
        return mode;
    }

    public enum Mode {
        DRY_RUN("--dry-run"), INTERACTIVE("--interactive"), AUTO("--auto");

        private final String string;

        Mode(String string) {
            this.string = string;
        }

        static Mode fromString(String value) {
            for (Mode m : values()) {
                if (m.string.equals(value)) {
                    return m;
                }
            }
            LOGGER.warn("Unknown command execution mode {}. Switching to default mode {}.", value, DRY_RUN.string);
            return DRY_RUN;
        }
    }
}
