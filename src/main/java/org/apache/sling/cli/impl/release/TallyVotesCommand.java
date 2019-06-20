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
package org.apache.sling.cli.impl.release;

import java.io.IOException;
import java.text.Collator;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import javax.mail.internet.InternetAddress;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.mail.Email;
import org.apache.sling.cli.impl.mail.Mailer;
import org.apache.sling.cli.impl.mail.VoteThreadFinder;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.nexus.StagingRepositoryFinder;
import org.apache.sling.cli.impl.people.Member;
import org.apache.sling.cli.impl.people.MembersFinder;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;

@Component(service = Command.class, property = {
        Command.PROPERTY_NAME_COMMAND_GROUP + "=" + TallyVotesCommand.GROUP,
        Command.PROPERTY_NAME_COMMAND_NAME + "=" + TallyVotesCommand.NAME
})
@CommandLine.Command(name = TallyVotesCommand.NAME,
                     description = "Counts votes cast for a release and generates the result email",
                     subcommands = CommandLine.HelpCommand.class)
public class TallyVotesCommand implements Command {

    static final String GROUP = "release";
    static final String NAME = "tally-votes";

    private static final Logger LOGGER = LoggerFactory.getLogger(TallyVotesCommand.class);

    @Reference
    private MembersFinder membersFinder;

    @Reference
    private StagingRepositoryFinder repoFinder;

    @Reference
    private VoteThreadFinder voteThreadFinder;

    @Reference
    private Mailer mailer;

    @CommandLine.Option(names = {"-r", "--repository"}, description = "Nexus repository id", required = true)
    private Integer repositoryId;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    // TODO - move to file
    private static final String EMAIL_TEMPLATE =
            "From: ##FROM## \n" +
            "To: \"Sling Developers List\" <dev@sling.apache.org>\n" + 
            "Subject: [RESULT] [VOTE] Release ##RELEASE_NAME##\n" + 
            "\n" + 
            "Hi,\n" + 
            "\n" + 
            "The vote has passed with the following result:\n" +
            "\n" + 
            "+1 (binding): ##BINDING_VOTERS##\n" + 
            "+1 (non-binding): ##NON_BINDING_VOTERS##\n" +
            "\n" +
            "I will copy this release to the Sling dist directory and\n" + 
            "promote the artifacts to the central Maven repository.\n" +
            "\n" +
            "Regards,\n" +
            "##USER_NAME##\n" +
            "\n";

    @Override
    public void run() {
        try {
            StagingRepository repository = repoFinder.find(repositoryId);
            List<Release> releases = Release.fromString(repository.getDescription());
            String releaseName = releases.stream().map(Release::getName).collect(Collectors.joining(", "));
            String releaseFullName = releases.stream().map(Release::getFullName).collect(Collectors.joining(", "));
            Set<String> bindingVoters = new LinkedHashSet<>();
            Set<String> nonBindingVoters = new LinkedHashSet<>();
            Collator collator = Collator.getInstance(Locale.US);
            collator.setDecomposition(Collator.NO_DECOMPOSITION);
            List<Email> emailThread = voteThreadFinder.findVoteThread(releaseName);
            if (emailThread.isEmpty()) {
                LOGGER.error("Could not find a corresponding email voting thread for release \"{}\".", releaseName);
            } else {
                emailThread.stream().skip(1).filter(this::isPositiveVote).forEachOrdered(
                        email -> {
                            String from = email.getFrom().getAddress();
                            String name = email.getFrom().getPersonal();
                            Member m = membersFinder.findByNameOrEmail(name, from);
                            if (m != null) {
                                if (m.isPMCMember()) {
                                    bindingVoters.add(m.getName());
                                } else {
                                    nonBindingVoters.add(m.getName());
                                }
                            } else {
                                nonBindingVoters.add(name);
                            }
                        }
                );
                Member currentMember = membersFinder.getCurrentMember();
                String email = EMAIL_TEMPLATE
                        .replace("##FROM##", new InternetAddress(currentMember.getEmail(), currentMember.getName()).toUnicodeString())
                        .replace("##RELEASE_NAME##", releaseFullName)
                        .replace("##BINDING_VOTERS##", String.join(", ", bindingVoters))
                        .replace("##USER_NAME##", membersFinder.getCurrentMember().getName());
                if (nonBindingVoters.isEmpty()) {
                    email = email.replace("##NON_BINDING_VOTERS##", "none");
                } else {
                    email = email.replace("##NON_BINDING_VOTERS##", String.join(", ", nonBindingVoters));
                }

                if (bindingVoters.size() >= 3) {
                    switch (reusableCLIOptions.executionMode) {
                        case DRY_RUN:
                            LOGGER.info("The following email would be sent from your @apache.org address (see the \"From:\" header):\n");
                            LOGGER.info(email);
                            break;
                        case INTERACTIVE:
                            String question ="Should the following email be sent from your @apache.org address (see the" +
                                    " \"From:\" header)?\n\n" + email;
                            InputOption answer = UserInput.yesNo(question, InputOption.YES);
                            if (InputOption.YES.equals(answer)) {
                                LOGGER.info("Sending email...");
                                mailer.send(email);
                                LOGGER.info("Done!");
                            } else if (InputOption.NO.equals(answer)) {
                                LOGGER.info("Aborted.");
                            }
                            break;
                        case AUTO:
                            LOGGER.info(email);
                            LOGGER.info("Sending email...");
                            mailer.send(email);
                            LOGGER.info("Done!");
                            break;
                    }
                } else {
                    LOGGER.info("Release {} does not have at least 3 binding votes.", releaseFullName);
                    LOGGER.info("Binding votes: {}.", String.join(", ", bindingVoters));
                }
            }
            
        } catch (IOException e) {
            LOGGER.warn("Command execution failed", e);
        }
    }

    // TODO - better detection of '+1' votes
    private boolean isPositiveVote(Email e) {
        return cleanup(e.getBody()).contains("+1");
    }

    private String cleanup(String subject) {
        String[] lines = subject.split("\\n");
        return Arrays.stream(lines)
            .filter( l -> !l.isEmpty() )
            .filter( l -> !l.startsWith(">"))
            .collect(Collectors.joining("\n"));
    }

}
