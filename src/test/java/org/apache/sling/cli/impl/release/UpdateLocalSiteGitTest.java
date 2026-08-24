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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.people.Member;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Exercises a <em>shallow</em> site checkout against a real JGit and real repositories.
 *
 * <p>{@link UpdateLocalSiteCommandTest} already drives the command against a real repository; what is
 * specific here is that the checkout is shallow, so a JGit upgrade that breaks shallow fetches, or a
 * server that refuses a push from a shallow clone, is caught here rather than during a release.
 *
 * <p>Everything happens between two local repositories, so no network access is involved.
 */
public class UpdateLocalSiteGitTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private Path upstream;
    private Path checkout;

    @Before
    public void createRepositories() throws Exception {
        upstream = tmp.newFolder("upstream.git").toPath();
        Path seed = tmp.newFolder("seed").toPath();

        try (Git bare = Git.init()
                .setBare(true)
                .setInitialBranch(UpdateLocalSiteCommand.SITE_BRANCH)
                .setDirectory(upstream.toFile())
                .call()) {
            assertTrue(bare.getRepository().getDirectory().exists());
        }

        // seed the upstream with the two files the site update edits
        try (Git git = Git.init()
                .setInitialBranch(UpdateLocalSiteCommand.SITE_BRANCH)
                .setDirectory(seed.toFile())
                .call()) {
            Path content = Files.createDirectories(seed.resolve("src/main/jbake/content"));
            Files.writeString(content.resolve("releases.md"), "seeded\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("src").call();
            git.commit()
                    .setMessage("seed")
                    .setAuthor("Seed", "seed@example.org")
                    .setCommitter("Seed", "seed@example.org")
                    .setSign(false)
                    .call();
            git.remoteAdd()
                    .setName("origin")
                    .setUri(new org.eclipse.jgit.transport.URIish(
                            upstream.toUri().toString()))
                    .call();
            git.push().call();
        }

        // a shallow, single-branch clone, matching what the command creates for the real site
        checkout = tmp.newFolder("checkout").toPath();
        try (Git cloned = Git.cloneRepository()
                .setURI(upstream.toUri().toString())
                .setDirectory(checkout.toFile())
                .setCloneAllBranches(false)
                .setBranch(UpdateLocalSiteCommand.SITE_BRANCH)
                .setDepth(1)
                .call()) {
            assertEquals(
                    UpdateLocalSiteCommand.SITE_BRANCH, cloned.getRepository().getBranch());
        }
    }

    @Test
    public void shallowCloneIsUsable() throws Exception {
        try (Git git = Git.open(checkout.toFile())) {
            assertFalse(
                    "the clone should be shallow",
                    git.getRepository().getObjectDatabase().getShallowCommits().isEmpty());
        }
    }

    @Test
    public void changesAreCommittedAndPushedWithTheReleaseManagerAsAuthorAndCommitter() throws Exception {
        UpdateLocalSiteCommand.ensureRepo(checkout.toString());
        Files.writeString(checkout.resolve("src/main/jbake/content/releases.md"), "released\n", StandardCharsets.UTF_8);

        UpdateLocalSiteCommand.commitAndPushSiteChanges(
                checkout.toString(),
                "Released Apache Sling Foo 1.2.0",
                "unused in AUTO",
                ExecutionMode.AUTO,
                new Credentials("johndoe", "secret"),
                new Member("johndoe", "John Doe", true));

        try (Git upstreamGit = Git.open(new File(upstream.toString()))) {
            RevCommit head = upstreamGit.log().setMaxCount(1).call().iterator().next();
            assertEquals("Released Apache Sling Foo 1.2.0", head.getFullMessage());

            PersonIdent author = head.getAuthorIdent();
            PersonIdent committer = head.getCommitterIdent();
            assertEquals("John Doe", author.getName());
            assertEquals("johndoe@apache.org", author.getEmailAddress());
            // the container has no git identity, so the committer must be set explicitly too
            assertEquals("John Doe", committer.getName());
            assertEquals("johndoe@apache.org", committer.getEmailAddress());
        }
    }

    @Test
    public void dryRunDoesNotPush() throws Exception {
        UpdateLocalSiteCommand.ensureRepo(checkout.toString());
        Files.writeString(checkout.resolve("src/main/jbake/content/releases.md"), "released\n", StandardCharsets.UTF_8);
        String upstreamHeadBefore = upstreamHead();

        UpdateLocalSiteCommand.commitAndPushSiteChanges(
                checkout.toString(),
                "Released Apache Sling Foo 1.2.0",
                "unused in DRY_RUN",
                ExecutionMode.DRY_RUN,
                new Credentials("johndoe", "secret"),
                new Member("johndoe", "John Doe", true));

        assertEquals("nothing may be pushed in a dry run", upstreamHeadBefore, upstreamHead());
    }

    private String upstreamHead() throws Exception {
        try (Git git = Git.open(new File(upstream.toString()))) {
            return git.log().setMaxCount(1).call().iterator().next().getName();
        }
    }
}
