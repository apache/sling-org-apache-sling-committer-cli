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

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.URIish;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;

/**
 * A stand-in for the {@code sling-site} repository: a real bare "upstream" seeded with the two files the
 * website update edits, plus a real checkout of it.
 *
 * <p>Used instead of mocking JGit. Mocked git commands keep passing whatever JGit actually does — they had
 * to be taught about {@code checkout()}, {@code setDepth()} and {@code setCommitter()} one breakage at a
 * time — whereas these tests exercise the real clone, fetch, commit and push. Everything is local, so no
 * network access is involved. This mirrors how the rest of the suite works: {@code MockJira} and
 * {@code MockNexus} are real local HTTP servers rather than mocked clients.
 */
class SiteRepository extends ExternalResource {

    /** The branch the website is published from. */
    static final String BRANCH = "master";

    private final TemporaryFolder tmp = new TemporaryFolder();

    private Path upstream;
    private Path checkout;

    @Override
    protected void before() throws Throwable {
        tmp.create();
        upstream = tmp.newFolder("upstream.git").toPath();
        Path seed = tmp.newFolder("seed").toPath();

        try (Git bare = Git.init()
                .setBare(true)
                .setInitialBranch(BRANCH)
                .setDirectory(upstream.toFile())
                .call()) {
            // created; nothing else to do
            bare.getRepository().getDirectory();
        }

        try (Git git =
                Git.init().setInitialBranch(BRANCH).setDirectory(seed.toFile()).call()) {
            // the real site fixtures, so the content updater runs against realistic input
            copyResource("/releases.md", seed.resolve("src/main/jbake/content/releases.md"));
            copyResource("/news.md", seed.resolve("src/main/jbake/content/news.md"));
            copyResource("/downloads.tpl", seed.resolve("src/main/jbake/templates/downloads.tpl"));
            git.add().addFilepattern("src").call();
            git.commit()
                    .setMessage("seed the site")
                    .setAuthor("Seed", "seed@example.org")
                    .setCommitter("Seed", "seed@example.org")
                    .setSign(false)
                    .call();
            git.remoteAdd()
                    .setName("origin")
                    .setUri(new URIish(upstream.toUri().toString()))
                    .call();
            git.push().call();
        }

        checkout = tmp.newFolder("checkout").toPath();
        try (Git ignored = Git.cloneRepository()
                .setURI(upstream.toUri().toString())
                .setDirectory(checkout.toFile())
                .setBranch(BRANCH)
                .call()) {
            // cloned
        }
    }

    @Override
    protected void after() {
        tmp.delete();
    }

    /** The directory the commands should be pointed at via {@code --site-checkout}. */
    String checkout() {
        return checkout.toString();
    }

    /** The upstream as a {@code file://} uri, so a clone from it honours a requested depth. */
    String upstreamUri() {
        return upstream.toUri().toString();
    }

    Path checkoutPath() {
        return checkout;
    }

    Path downloadsTemplate() {
        return checkout.resolve("src/main/jbake/templates/downloads.tpl");
    }

    Path releases() {
        return checkout.resolve("src/main/jbake/content/releases.md");
    }

    /** The commit at the tip of the upstream, i.e. what a push actually landed. */
    RevCommit upstreamHead() throws Exception {
        try (Git git = Git.open(new File(upstream.toString()))) {
            return git.log().setMaxCount(1).call().iterator().next();
        }
    }

    String upstreamFile(String path) throws Exception {
        try (Git git = Git.open(new File(upstream.toString()))) {
            var head = git.getRepository().resolve(BRANCH + ":" + path);
            return new String(git.getRepository().open(head).getBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void copyResource(String resource, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        try (InputStream in = SiteRepository.class.getResourceAsStream(resource)) {
            Files.copy(in, target);
        }
    }
}
