[![Apache Sling](https://sling.apache.org/res/logos/sling.png)](https://sling.apache.org)

&#32;[![Build Status](https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-committer-cli/job/master/badge/icon)](https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-committer-cli/job/master/)&#32;[![Test Status](https://img.shields.io/jenkins/tests.svg?jobUrl=https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-committer-cli/job/master/)](https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-committer-cli/job/master/test/?width=800&height=600)&#32;[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=apache_sling-org-apache-sling-committer-cli&metric=coverage)](https://sonarcloud.io/dashboard?id=apache_sling-org-apache-sling-committer-cli)&#32;[![Sonarcloud Status](https://sonarcloud.io/api/project_badges/measure?project=apache_sling-org-apache-sling-committer-cli&metric=alert_status)](https://sonarcloud.io/dashboard?id=apache_sling-org-apache-sling-committer-cli) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

# Apache Sling Committer CLI tool

This module is part of the [Apache Sling](https://sling.apache.org) project.

This module provides a command-line tool which automates various Sling development tasks. The tool is packaged
as a docker image.

## Prerequisites

Before releasing you need the following in place. These are split between the **Maven side** (used by
`mvn release:prepare`/`release:perform` in the project being released) and the **CLI side** (used by
this Docker tool).

### 1. ASF credentials for the CLI (`docker-env`)

To make credentials available to the docker image, use a docker env file. A sample file is stored at
`docker-env.sample`; copy it to `docker-env` and fill in your own information. At minimum it must
provide your ASF credentials (these are read by the CLI for Nexus, JIRA, Whimsy and the mailing
lists):

    ASF_USERNAME=your-apache-id
    ASF_PASSWORD=your-apache-password

### 2. GPG signing key

* Generate a code-signing key and publish it: `gpg --send-keys <KEY_ID>` to `keys.openpgp.org`, and
  add its public block to the Sling `KEYS` file at
  <https://dist.apache.org/repos/dist/release/sling/KEYS> (PMC members can commit there directly).
* Note the key id (e.g. `0A1B2C3D4E5F6789`) — it goes into `settings.xml` below.

### 3. Maven `~/.m2/settings.xml`

Recent Apache/Sling parent POMs use **maven-gpg-plugin 3.x**, which changed how the GPG passphrase is
supplied. The old approach of a `<gpg.passphrase>` property in the `apache-release` profile is **no
longer used** — the plugin now reads the passphrase from a **server** whose id is given by
`gpg.passphraseServerId` (default: `gpg.passphrase`) and decrypts it via `settings-security.xml`.

```xml
<settings>
  <servers>
    <!-- ASF Nexus credentials (encrypted, see settings-security.xml) -->
    <server>
      <id>apache.snapshots.https</id>
      <username>your-apache-id</username>
      <password>{ENCRYPTED}</password>
    </server>
    <server>
      <id>apache.releases.https</id>
      <username>your-apache-id</username>
      <password>{ENCRYPTED}</password>
    </server>

    <!-- maven-gpg-plugin 3.x reads the passphrase from THIS server id (gpg.passphraseServerId
         defaults to "gpg.passphrase") and decrypts it via settings-security.xml. -->
    <server>
      <id>gpg.passphrase</id>
      <passphrase>{ENCRYPTED}</passphrase>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>apache-release</id>
      <properties>
        <apache.availid>your-apache-id</apache.availid>
        <!-- required so gpg does not try to open an interactive pinentry dialog -->
        <gpg.pinentryMode>loopback</gpg.pinentryMode>
        <!-- the signing key id from step 2 -->
        <gpg.keyname>YOUR_KEY_ID</gpg.keyname>
        <!-- SMTP host used by the parent POM's announcement tooling -->
        <smtp.host>smtp.gmail.com</smtp.host>
      </properties>
    </profile>
  </profiles>
</settings>
```

Encrypt the passwords/passphrase with `mvn --encrypt-password` (server passwords) and store the master
password in `~/.m2/settings-security.xml`:

```xml
<settingsSecurity>
  <master>{ENCRYPTED_MASTER_PASSWORD}</master>
</settingsSecurity>
```

## Building

The Docker image (`apache/sling-cli:latest`) is produced by the `docker:build` goal of the
[fabric8 docker-maven-plugin](https://dmp.fabric8.io/). The image bundles the project jar via the
generated `*-app.slingfeature` descriptor; the `slingfeature-maven-plugin` resolves the project's
own bundle from the reactor, so the image always contains the jar built in the same invocation.

A single command builds (and tests) the project and the image:

    mvn clean package docker:build

No prior `mvn install` is needed. The `docker:build` execution is also bound to the `package`
phase, so the CI build (`mvn package`) builds the image too.

To confirm the image contains the expected commands:

    docker run --env-file=./docker-env apache/sling-cli release help

## Launching

After building, run the image with:

    docker run --env-file=./docker-env apache/sling-cli

This invocation produces a list of available commands.

## Commands

The commands can be executed in 3 different modes:

  * `DRY_RUN` (default mode) - commands only list their output without performing any actions on the user's behalf
  * `INTERACTIVE` - commands list their output but ask for user confirmation when it comes to performing an action on the user's behalf
  * `AUTO` - commands list their output and assume that all questions are provided the default answers when it comes to performing an 
  action on the user's behalf

To select a non-default execution mode provide the mode as an argument to the command:

    docker run -it --env-file=./docker-env apache/sling-cli release prepare-email --repository=$STAGING_REPOSITORY 
    --execution-mode=INTERACTIVE

Note that for running commands in the `INTERACTIVE` mode you need to run the Docker container in interactive mode with a pseudo-tty 
attached (e.g. `docker run -it ...`).

### Release workflow

The full release lifecycle has **manual Maven steps** (run in the project being released) and **CLI
steps** (run via this Docker tool). The CLI commands take the numeric staging repository id via
`-r`/`--repository` (e.g. `3103` for `orgapachesling-3103`). Append `-x AUTO` to actually perform an
action (the default mode is `DRY_RUN`).

#### Manual steps in the project being released (Maven)

These are **not** part of this tool — run them in the module you are releasing, with the prerequisites
from the section above configured:

1. Make sure the parent POM is up to date and the build is green (`mvn clean verify`).
2. Dry-run the release and check that only `<version>`/`<scm>` change:

       mvn release:prepare -DdryRun=true
       mvn release:clean

3. Deploy a snapshot and confirm `META-INF/LICENSE` and `META-INF/NOTICE` are in the jar:

       mvn deploy

4. Prepare and perform the release (creates the tag, bumps to the next SNAPSHOT, signs and stages the
   artifacts to Nexus):

       mvn release:prepare
       mvn release:perform

   Note the staging repository id from the `release:perform` output (a line like
   `…/orgapachesling-1087`). If it scrolls past, `release list` (below) shows open repos too.

#### CLI steps (this tool)

After `release:perform` has staged the artifacts, drive the rest with the CLI:

0. **Find the staging repository id** if you did not capture it. `release list` shows every staging
   repo with its `[open]`/`[closed]` state and description; a freshly staged one is `[open]`:

       docker run --env-file=./docker-env apache/sling-cli release list

1. **Close** the staging repository. The description is derived automatically from the staged POM's
   `<name>` + `<version>` (e.g. _Apache Sling Feature Model Launcher 1.3.6_) by browsing the
   repository content, so it works even though an open repository is not yet in the Lucene index:

       docker run --env-file=./docker-env apache/sling-cli release close-staging --repository=$STAGING_REPOSITORY_ID --execution-mode=AUTO

2. **Verify** the artifacts' signatures, hashes and CI status:

       docker run --env-file=./docker-env apache/sling-cli release verify --repository=$STAGING_REPOSITORY_ID

3. **Generate the vote email**:

       docker run --env-file=./docker-env apache/sling-cli release prepare-email --repository=$STAGING_REPOSITORY_ID --execution-mode=AUTO

4. After the 72h vote, **tally the votes** and generate the result email. PMC membership is detected
   automatically from your ASF id: if you are a PMC member the email says you will copy the release to
   the dist directory yourself; otherwise it asks a PMC member to perform the dist upload:

       docker run --env-file=./docker-env apache/sling-cli release tally-votes --repository=$STAGING_REPOSITORY_ID --execution-mode=AUTO

5. **Finalize** the release (post successful vote). This runs, in order: promote to Maven Central,
   create the next Jira version, release the current Jira version, update the Apache Reporter, and
   update the Sling website:

       docker run --env-file=./docker-env apache/sling-cli release finalize --repository=$STAGING_REPOSITORY_ID --execution-mode=AUTO

   When the current user is detected as a PMC member, `finalize` additionally publishes to
   `dist.apache.org` (requires `subversion`, which is bundled in the image). The previous version to
   remove from `dist/release` is deduced automatically from the directory contents, so no extra flag
   is needed:

       docker run --env-file=./docker-env apache/sling-cli release finalize --repository=$STAGING_REPOSITORY_ID --execution-mode=AUTO

   PMC membership is determined from your ASF id (via Whimsy). A non-PMC committer's `finalize` skips
   the dist upload and the `tally-votes` result email asks a PMC member to perform it.

   The last step updates the website: it adds the release to `content/releases.md` and bumps the
   matching entries in `templates/downloads.tpl`, then commits and pushes to `sling-site` over gitbox
   using the same ASF credentials. Entries are matched on the *artifact id* rather than on the display
   name, because the two often differ (*Tracer* is listed as *Log Tracer*) and one release can own
   several entries. Only entries on the same major version are touched, so a maintenance release of an
   older line (e.g. Resource Resolver 1.12.x while the page lists 2.x) never downgrades the page. If an
   artifact has no entry at all it is reported so it can be added by hand, which is also what the
   release guide asks for when a brand new module is released.

   The CLI keeps its own checkout of `sling-site` and never looks for one you may already have. It lives
   in `$HOME/.sling-cli/sling-site`, which inside the container is `/root/.sling-cli/sling-site` — so by
   default it is discarded with the container and re-cloned on every run. Only the tip of `master` is
   cloned, which keeps that to about 25 MB rather than the ~380 MB a full clone of the site would take, but
   to avoid re-cloning altogether mount a directory that outlives the container:

       docker run --env-file=./docker-env \
           -v "$HOME/.sling-cli:/root/.sling-cli" \
           apache/sling-cli release finalize --repository=$STAGING_REPOSITORY_ID --execution-mode=AUTO

   Use `SLING_CLI_SITE_CHECKOUT` (or the `sling.cli.site.checkout` system property) to put the checkout
   somewhere else. Point it at a directory dedicated to this purpose rather than at a clone you work in:
   `master` is checked out and hard-reset to `origin/master` before each run, so anything uncommitted
   there is discarded, and the release is then committed and pushed from it.

   The news page is deliberately *not* part of `finalize` — the release guide only asks for a news entry
   when a release warrants an announcement. Run it by hand for those:

       docker run --env-file=./docker-env apache/sling-cli release update-news --release "Apache Sling Foo 1.2.0" --link /documentation/bundles/foo.html --execution-mode=AUTO

If the vote does not pass, **drop** the staging repository:

    docker run --env-file=./docker-env apache/sling-cli release drop --repository=$STAGING_REPOSITORY_ID --execution-mode=AUTO

### Command reference

| Command | Description |
|---------|-------------|
| `release list` | List closed staging repositories |
| `release close-staging -r <id>` | Close an open staging repo, setting the description from the staged POM |
| `release verify -r <id>` | Download and verify artifact signatures, hashes and CI status |
| `release prepare-email -r <id>` | Generate (and send) the `[VOTE]` email |
| `release tally-votes -r <id>` | Count votes and generate the `[RESULT]` email (PMC membership auto-detected; non-PMC email asks a PMC member to do the dist upload) |
| `release promote -r <id>` | Promote a closed staging repo to Maven Central |
| `release update-dist -r <id>` | Move artifacts to `dist.apache.org` (PMC only); previous version auto-deduced, override with `--previous-version <v>` |
| `release finalize -r <id>` | Promote + Jira + Reporter + website in one step; also updates `dist.apache.org` when you are a PMC member |
| `release drop -r <id>` | Drop a staging repository (failed vote / cleanup) |
| `release create-new-jira-version -r <id>` | Create the next Jira version and move unresolved issues |
| `release release-jira-version -r <id>` | Mark the Jira version as released and close fixed issues |
| `release update-reporter -r <id>` | Register the release with the Apache Reporter System |
| `release update-local-site -r <id>` | Update `releases.md` and `downloads.tpl` in a `sling-site` checkout, then commit and push |
| `release update-news -r <id>` | Announce a release on the news page; run only for releases worth announcing (not part of `finalize`) |

## Assumptions

This tool assumes that the name of the staging repository matches the one of the version in Jira. For instance, the
staging repositories are usually named _Apache Sling Foo 1.2.0_. It is then expected that the Jira version is
named _Foo 1.2.0_. Otherwise the link between the staging repository and the Jira release can not be found.

It is allowed for staging repository names to have an _RC_ suffix, which may include a number, so that _RC_, _RC1_, _RC25_ are
all valid suffixes.  
