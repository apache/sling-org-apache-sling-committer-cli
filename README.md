[![Apache Sling](https://sling.apache.org/res/logos/sling.png)](https://sling.apache.org)

&#32;[![Build Status](https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-committer-cli/job/master/badge/icon)](https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-committer-cli/job/master/)&#32;[![Test Status](https://img.shields.io/jenkins/tests.svg?jobUrl=https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-committer-cli/job/master/)](https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-committer-cli/job/master/test/?width=800&height=600)&#32;[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=apache_sling-org-apache-sling-committer-cli&metric=coverage)](https://sonarcloud.io/dashboard?id=apache_sling-org-apache-sling-committer-cli)&#32;[![Sonarcloud Status](https://sonarcloud.io/api/project_badges/measure?project=apache_sling-org-apache-sling-committer-cli&metric=alert_status)](https://sonarcloud.io/dashboard?id=apache_sling-org-apache-sling-committer-cli) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

# Apache Sling Committer CLI tool

This module is part of the [Apache Sling](https://sling.apache.org) project.

This module provides a command-line tool which automates various Sling development tasks. The tool is packaged
as a docker image.

## Configuration

To make various credentials and configurations available to the docker image it is recommended to use a docker env file.
A sample file is stored at `docker-env.sample`. Copy this file to `docker-env` and fill in your own information.

## Launching

The image is built using `mvn package`. Afterwards it may be run with

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

Listing active releases

    docker run --env-file=./docker-env apache/sling-cli release list

Generating a release vote email

    docker run --env-file=./docker-env apache/sling-cli release prepare-email --repository=$STAGING_REPOSITORY_ID
    
Generating a release vote result email

    docker run --env-file=./docker-env apache/sling-cli release tally-votes --repository=$STAGING_REPOSITORY_ID
    
Generating the website update (only diff for now)

	docker run --env-file=docker-env apache/sling-cli release update-local-site --repository=$STAGING_REPOSITORY_ID

## Assumptions

This tool assumes that the name of the staging repository matches the one of the version in Jira. For instance, the
staging repositories are usually named _Apache Sling Foo 1.2.0_. It is then expected that the Jira version is
named _Foo 1.2.0_. Otherwise the link between the staging repository and the Jira release can not be found.

It is allowed for staging repository names to have an _RC_ suffix, which may include a number, so that _RC_, _RC1_, _RC25_ are
all valid suffixes.  
