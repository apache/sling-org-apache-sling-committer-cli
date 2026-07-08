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
import java.util.Set;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import picocli.CommandLine;

/**
 * Base for the post-vote commands that act on one or more releases identified either by a Nexus
 * staging repository id ({@code -r}) or, once the staging repository no longer exists, by release
 * name ({@code --release}). Holds the shared options and the resolution logic; the concrete commands
 * keep their own service references and pass the {@link RepositoryService} in.
 */
abstract class AbstractReleaseCommand implements Command {

    @CommandLine.Option(
            names = {"-r", "--repository"},
            description = "Nexus staging repository id to derive the release(s) from")
    protected Integer repositoryId;

    @CommandLine.Option(
            names = {"--release"},
            description = "Release name(s) to act on, e.g. \"Apache Sling Foo 1.2.0\" (comma-separated for multiple)."
                    + " Use instead of --repository when the staging repository no longer exists,"
                    + " e.g. after the release has been promoted.")
    protected String releaseName;

    /** Resolves the target releases from either the {@code --release} names or the {@code -r} repository id. */
    protected Set<Release> resolveReleases(RepositoryService repositoryService) throws IOException {
        if (releaseName != null && !releaseName.isBlank()) {
            return Set.copyOf(Release.fromString(releaseName));
        }
        if (repositoryId == null) {
            return Set.of();
        }
        return repositoryService.getReleases(repositoryService.find(repositoryId));
    }
}
