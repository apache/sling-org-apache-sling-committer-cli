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
package org.apache.sling.cli.impl.release;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.ci.CIStatusValidator;
import org.apache.sling.cli.impl.nexus.Artifact;
import org.apache.sling.cli.impl.nexus.LocalRepository;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.pgp.HashValidator;
import org.apache.sling.cli.impl.pgp.PGPSignatureValidator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.util.encoders.Hex;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;

@Component(service = Command.class, property = {
        Command.PROPERTY_NAME_COMMAND_GROUP + "=" + VerifyReleasesCommand.GROUP,
        Command.PROPERTY_NAME_COMMAND_NAME + "=" + VerifyReleasesCommand.NAME })
@CommandLine.Command(name = VerifyReleasesCommand.NAME, description = "Downloads the staging repository and verifies the artifacts' signatures and hashes.", subcommands = CommandLine.HelpCommand.class)
public class VerifyReleasesCommand implements Command {

    private static final Logger LOGGER = LoggerFactory.getLogger(VerifyReleasesCommand.class);

    static final String GROUP = "release";
    static final String NAME = "verify";

    @Reference
    private RepositoryService repositoryService;

    @Reference
    private PGPSignatureValidator pgpSignatureValidator;

    @Reference
    private CIStatusValidator ciStatusValidator;

    @Reference
    private HashValidator hashValidator;

    @CommandLine.Option(names = { "-r", "--repository" }, description = "Nexus repository id", required = true)
    private Integer repositoryId;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    @Override
    public void run() {
        int checksRun = 0;
        int failedChecks = 0;
        try {
            LocalRepository repository = repositoryService.download(repositoryService.find(repositoryId));
            Path repositoryRootPath = repository.getRootFolder();
            Artifact pom = null;
            Path pomPath = null;
            for (Artifact artifact : repository.getArtifacts()) {
                if ("pom".equals(artifact.getType())) {
                    pom = artifact;
                    pomPath = repositoryRootPath.resolve(artifact.getRepositoryRelativePath());
                }
                Path artifactFilePath = repositoryRootPath.resolve(artifact.getRepositoryRelativePath());
                Path artifactSignaturePath = repositoryRootPath.resolve(artifact.getRepositoryRelativeSignaturePath());
                PGPSignatureValidator.ValidationResult validationResult = pgpSignatureValidator.verify(artifactFilePath,
                        artifactSignaturePath);
                checksRun++;
                if (!validationResult.isValid()) {
                    failedChecks++;
                }
                HashValidator.ValidationResult sha1validationResult = hashValidator.validate(artifactFilePath,
                        repositoryRootPath.resolve(artifact.getRepositoryRelativeSha1SumPath()), "SHA-1");
                checksRun++;
                if (!sha1validationResult.isValid()) {
                    failedChecks++;
                }
                HashValidator.ValidationResult md5validationResult = hashValidator.validate(artifactFilePath,
                        repositoryRootPath.resolve(artifact.getRepositoryRelativeMd5SumPath()), "MD5");
                checksRun++;
                if (!md5validationResult.isValid()) {
                    failedChecks++;
                }
                LOGGER.info("\n{}", artifactFilePath.getFileName().toString());
                PGPPublicKey key = validationResult.getKey();
                LOGGER.info("GPG: {}", validationResult.isValid()
                        ? String.format("signed by %s with key (id=0x%X; " + "fingerprint=%s)", getKeyUserId(key),
                                key.getKeyID(), Hex.toHexString(key.getFingerprint()).toUpperCase(Locale.US))
                        : "INVALID");
                LOGGER.info("SHA-1: {}",
                        sha1validationResult.isValid()
                                ? String.format("VALID (%s)", sha1validationResult.getActualHash())
                                : String.format("INVALID (expected %s, got %s)", sha1validationResult.getExpectedHash(),
                                        sha1validationResult.getActualHash()));
                LOGGER.info("MD-5: {}",
                        md5validationResult.isValid() ? String.format("VALID (%s)", md5validationResult.getActualHash())
                                : String.format("INVALID (expected %s, got %s)", md5validationResult.getExpectedHash(),
                                        md5validationResult.getActualHash()));
            }
            if (pom != null && pomPath != null) {
                if (ciStatusValidator.shouldCheck(pom, pomPath)) {
                    CIStatusValidator.ValidationResult ciValidationResult = ciStatusValidator.isValid(pomPath);
                    LOGGER.info("\nCI Status: {}",
                            ciValidationResult.isValid() ? String.format("VALID: %n%s", ciValidationResult.getMessage())
                                    : String.format("INVALID: %n%s", ciValidationResult.getMessage()));
                    checksRun++;
                    if (!ciValidationResult.isValid()) {
                        failedChecks++;
                    }
                }
            }

        } catch (IOException e) {
            LOGGER.error("Command execution failed.", e);
        }

        LOGGER.info("\n\nRelease Summary: {}\n\n",
                failedChecks == 0 ? String.format("VALID (%d checks executed)", checksRun)
                        : String.format("INVALID (%d of %d checks failed)", failedChecks, checksRun));
    }

    private String getKeyUserId(PGPPublicKey key) {
        Iterator<String> iterator = key.getUserIDs();
        return iterator.hasNext() ? iterator.next() : "unknown";
    }
}
