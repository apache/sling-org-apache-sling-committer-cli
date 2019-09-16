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

import java.io.Console;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserInput {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserInput.class);
    private static final InputOption[] YES_NO = new InputOption[]{InputOption.YES, InputOption.NO};

    private UserInput() {}

    public static InputOption yesNo(String question, InputOption defaultOption) {
        LOGGER.info(question);
        Set<InputOption> answers = new LinkedHashSet<>(Arrays.asList(YES_NO));
        String choice =
                answers.stream().map(InputOption::toString).collect(Collectors.joining("/")) + "? [" + defaultOption.toString() +
                        "]: ";
        while (true) {
            System.out.print(choice);
            Console console = System.console();
            if (console != null) {
                String answerMnemonic = console.readLine();
                if ("".equals(answerMnemonic)) {
                    return defaultOption;
                }
                for (InputOption o : YES_NO) {
                    if (o.getMnemonic().equals(answerMnemonic)) {
                        return o;
                    }
                }
            } else {
                throw new IllegalStateException("System console unavailable.");
            }
        }
    }

}
