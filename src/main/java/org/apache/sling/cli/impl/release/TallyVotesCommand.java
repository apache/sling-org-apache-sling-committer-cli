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

import javax.mail.internet.InternetAddress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.DateProvider;
import org.apache.sling.cli.impl.InputOption;
import org.apache.sling.cli.impl.UserInput;
import org.apache.sling.cli.impl.mail.Email;
import org.apache.sling.cli.impl.mail.Mailer;
import org.apache.sling.cli.impl.mail.VoteThreadFinder;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.people.Member;
import org.apache.sling.cli.impl.people.MembersFinder;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

@Component(
        service = Command.class,
        property = {
            Command.PROPERTY_NAME_COMMAND_GROUP + "=" + TallyVotesCommand.GROUP,
            Command.PROPERTY_NAME_COMMAND_NAME + "=" + TallyVotesCommand.NAME
        })
@CommandLine.Command(
        name = TallyVotesCommand.NAME,
        description = "Counts votes cast for a release and generates the result email",
        subcommands = CommandLine.HelpCommand.class)
public class TallyVotesCommand implements Command {

    static final String GROUP = "release";
    static final String NAME = "tally-votes";

    private static final Logger LOGGER = LoggerFactory.getLogger(TallyVotesCommand.class);

    @Reference
    private MembersFinder membersFinder;

    @Reference
    private RepositoryService repositoryService;

    @Reference
    private VoteThreadFinder voteThreadFinder;

    @Reference
    private Mailer mailer;

    @Reference
    private DateProvider dateProvider;

    @CommandLine.Option(
            names = {"-r", "--repository"},
            description = "Nexus repository id",
            required = true)
    private Integer repositoryId;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    /** Prefix of the subject of the email that opens a vote thread. */
    private static final String VOTE_SUBJECT_PREFIX = "[VOTE]";

    /** The steps {@link FinalizeCommand} performs, in the order it performs them. */
    private static final String FINALIZE_STEPS = "  1. copy the artifacts to the Sling dist directory\n"
            + "     (https://dist.apache.org/repos/dist/release/sling/)\n"
            + "  2. promote the staged artifacts to the central Maven repository\n"
            + "  3. create the next JIRA version and move any unresolved issues to it\n"
            + "  4. mark the JIRA version as released\n"
            + "  5. add the release to the Apache Reporter System\n"
            + "  6. update the Sling website: the releases list and the downloads page";

    private static final String EMAIL_TEMPLATE;

    static {
        try {
            EMAIL_TEMPLATE = IOUtils.toString(
                    TallyVotesCommand.class.getClassLoader().getResourceAsStream("templates/tally-votes.email"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read embedded email template.", e);
        }
    }

    @Override
    public Integer call() {
        try {
            StagingRepository repository = repositoryService.find(repositoryId);
            Set<Release> releases = repositoryService.getReleases(repository);
            String releaseFullName = releases.stream().map(Release::getFullName).collect(Collectors.joining(", "));
            Set<String> bindingVoters = new LinkedHashSet<>();
            Set<String> nonBindingVoters = new LinkedHashSet<>();
            Collator collator = Collator.getInstance(Locale.US);
            collator.setDecomposition(Collator.NO_DECOMPOSITION);
            List<Email> emailThread = voteThreadFinder.findVoteThread(releaseFullName);
            if (emailThread.isEmpty()) {
                LOGGER.error("Could not find a corresponding email voting thread for release \"{}\".", releaseFullName);
            } else {
                Email voteEmail = findVoteEmail(emailThread);
                if (voteEmail == null) {
                    LOGGER.warn(
                            "Could not identify the [VOTE] email in the thread for release \"{}\"; it may have been "
                                    + "sent before the start of the lookup window. The result email will not be sent "
                                    + "as a reply and the first email in the thread is assumed to be the [VOTE] one.",
                            releaseFullName);
                }
                Email threadStart = voteEmail != null ? voteEmail : emailThread.get(0);
                emailThread.stream()
                        .filter(email -> email != threadStart)
                        .filter(this::isPositiveVote)
                        .forEachOrdered(email -> {
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
                        });
                Member currentMember = membersFinder.getCurrentMember();
                String email = EMAIL_TEMPLATE
                        .replace(
                                "##FROM##",
                                new InternetAddress(currentMember.getEmail(), currentMember.getName())
                                        .toUnicodeString())
                        .replace("##DATE##", dateProvider.getCurrentDateForEmailHeader())
                        .replace("##REPLY_HEADERS##", replyHeaders(voteEmail))
                        .replace("##RELEASE_NAME##", releaseFullName)
                        .replace("##BINDING_VOTERS##", String.join(", ", bindingVoters))
                        .replace("##CLOSING_ACTION##", closingAction(releaseFullName, currentMember.isPMCMember()))
                        .replace(
                                "##USER_NAME##",
                                membersFinder.getCurrentMember().getName());
                if (nonBindingVoters.isEmpty()) {
                    email = email.replace("##NON_BINDING_VOTERS##", "none");
                } else {
                    email = email.replace("##NON_BINDING_VOTERS##", String.join(", ", nonBindingVoters));
                }

                if (bindingVoters.size() >= 3) {
                    switch (reusableCLIOptions.executionMode) {
                        case DRY_RUN:
                            LOGGER.info(
                                    "The following email would be sent from your @apache.org address (see the \"From:\" header):\n");
                            LOGGER.info(email);
                            break;
                        case INTERACTIVE:
                            String question =
                                    "Should the following email be sent from your @apache.org address (see the"
                                            + " \"From:\" header)?\n\n" + email;
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
                    LOGGER.info(
                            "Binding votes: {}.", bindingVoters.isEmpty() ? "none" : String.join(", ", bindingVoters));
                    LOGGER.info(
                            "Non-binding votes: {}.",
                            nonBindingVoters.isEmpty() ? "none" : String.join(", ", bindingVoters));
                    return CommandLine.ExitCode.USAGE;
                }
            }

        } catch (IOException e) {
            LOGGER.warn("Command execution failed", e);
            return CommandLine.ExitCode.SOFTWARE;
        }
        return CommandLine.ExitCode.OK;
    }

    /**
     * Returns the email that opened the vote thread, identified by its subject. Replies carry a
     * {@code Re:}-style prefix and the result email of an earlier run carries a {@code [RESULT]} one,
     * so only the original vote email starts with {@value #VOTE_SUBJECT_PREFIX}. Position in the
     * thread is not a reliable indicator: the archive search only looks back a fixed period, so the
     * vote email is missing from the results when the vote was opened before that window starts.
     *
     * @param emailThread the emails found for the release
     * @return the vote email, or {@code null} if the thread does not contain one
     */
    private Email findVoteEmail(List<Email> emailThread) {
        return emailThread.stream()
                .filter(email ->
                        email.getSubject() != null && email.getSubject().startsWith(VOTE_SUBJECT_PREFIX))
                .findFirst()
                .orElse(null);
    }

    /**
     * Builds the headers that make the result email a reply to the vote email, so that both are part
     * of a single thread. Returns an empty string when the vote email or its {@code Message-ID} could
     * not be determined, in which case the email is sent standalone.
     *
     * @param voteEmail the vote email to reply to, may be {@code null}
     * @return the {@code In-Reply-To} and {@code References} headers, each terminated by a newline
     */
    private String replyHeaders(Email voteEmail) {
        if (voteEmail == null) {
            return "";
        }
        String messageId = voteEmail.getMessageId();
        if (messageId == null || messageId.isBlank()) {
            LOGGER.warn("The [VOTE] email has no Message-ID; the result email will not be sent as a reply.");
            return "";
        }
        messageId = messageId.trim();
        return "In-Reply-To: " + messageId + "\n" + "References: " + messageId + "\n";
    }

    /**
     * Builds the closing paragraph of the result email. Finalizing a release means copying it to the
     * dist directory first and only then promoting the artifacts to Maven Central. Because the dist
     * upload is restricted to PMC members, a non-PMC release manager cannot perform the finalization
     * in the correct order and must ask a PMC member to finalize the release. PMC membership is derived
     * from the current user, so no flag is needed. Both variants list the same {@link #FINALIZE_STEPS},
     * so the email states what finalizing actually involves rather than only its first two steps.
     */
    private String closingAction(String releaseFullName, boolean isPmcMember) {
        if (isPmcMember) {
            return "I will finalize this release:\n\n" + FINALIZE_STEPS;
        }
        return "This release still needs to be finalized:\n\n" + FINALIZE_STEPS + "\n\n"
                + "Steps 1 and 5 require PMC membership, which I do not have.\n\n"
                + "ACTION NEEDED: can a PMC member please finalize " + releaseFullName + "?";
    }

    // TODO - better detection of '+1' votes
    private boolean isPositiveVote(Email e) {
        return cleanup(e.getBody()).contains("+1");
    }

    private String cleanup(String subject) {
        String[] lines = subject.split("\\n");
        return Arrays.stream(lines)
                .filter(l -> !l.isEmpty())
                .filter(l -> !l.startsWith(">"))
                .collect(Collectors.joining("\n"));
    }
}
