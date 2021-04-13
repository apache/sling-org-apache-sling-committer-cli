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

import java.util.concurrent.Callable;

/**
 * Marker interface for {@code Commands} supported by the Apache Sling Committer CLI.
 * The {@code call} method is expected to return on of the exit codes found in {@code CommandLine.ExitCode}
 * 
 * @see picocli.CommandLine.ExitCode
 */
public interface Command extends Callable<Integer> {
    
    String PROPERTY_NAME_COMMAND_GROUP = "command.group";
    String PROPERTY_NAME_COMMAND_NAME = "command.name";

}
