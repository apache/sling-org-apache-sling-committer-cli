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
import org.apache.sling.cli.impl.nexus.Artifact;
import org.apache.sling.cli.impl.nexus.LocalRepository;
import org.apache.sling.cli.impl.nexus.RepositoryDownloader;
import org.apache.sling.cli.impl.nexus.StagingRepositoryFinder;
import org.apache.sling.cli.impl.pgp.PGPSignaturesValidator;
import org.apache.sling.cli.impl.pgp.SHA1HashResult;
import org.apache.sling.cli.impl.pgp.SHA1HashValidator;
import org.apache.sling.cli.impl.pgp.SignatureVerificationResult;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.util.encoders.Hex;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;

@Component(service = Command.class,
           property = {
                   Command.PROPERTY_NAME_COMMAND_GROUP + "=" + VerifyReleasesCommand.GROUP,
                   Command.PROPERTY_NAME_COMMAND_NAME + "=" + VerifyReleasesCommand.NAME
           })
@CommandLine.Command(name = VerifyReleasesCommand.NAME,
                     description = "Downloads the staging repository and verifies the artifacts' signatures and hashes.",
                     subcommands = CommandLine.HelpCommand.class)
public class VerifyReleasesCommand implements Command {

    private static final Logger LOGGER = LoggerFactory.getLogger(VerifyReleasesCommand.class);

    static final String GROUP = "release";
    static final String NAME = "verify";

    @Reference
    private StagingRepositoryFinder stagingRepositoryFinder;

    @Reference
    private RepositoryDownloader repositoryDownloader;

    @Reference
    private PGPSignaturesValidator pgpSignaturesValidator;

    @Reference
    private SHA1HashValidator sha1HashValidator;

    @CommandLine.Option(names = {"-r", "--repository"},
                        description = "Nexus repository id",
                        required = true)
    private Integer repositoryId;

    @CommandLine.Mixin
    private ReusableCLIOptions reusableCLIOptions;

    @Override
    public void run() {
        try {
            LocalRepository repository = repositoryDownloader.download(stagingRepositoryFinder.find(repositoryId));
            Path repositoryRootPath = repository.getRootFolder();
            for (Artifact artifact : repository.getArtifacts()) {
                Path artifactFilePath = repositoryRootPath.resolve(artifact.getRepositoryRelativePath());
                Path artifactSignaturePath = repositoryRootPath.resolve(artifact.getRepositoryRelativeSignaturePath());
                SignatureVerificationResult signatureVerificationResult = pgpSignaturesValidator.verify(artifactFilePath,
                        artifactSignaturePath);
                SHA1HashResult sha1HashResult = sha1HashValidator.validate(artifactFilePath,
                        repositoryRootPath.resolve(artifact.getRepositoryRelativeSha1SumPath()));
                LOGGER.info("\n" + artifactFilePath.getFileName().toString());
                PGPPublicKey key = signatureVerificationResult.getKey();
                LOGGER.info("GPG: {}", signatureVerificationResult.isValid() ? String.format("signed by %s with key (id=0x%X; " +
                        "fingerprint=%s)", getKeyUserId(key), key.getKeyID(),
                        Hex.toHexString(key.getFingerprint()).toUpperCase(Locale.US)) : "INVALID");
                LOGGER.info("SHA-1: {}", sha1HashResult.isValid() ? String.format("VALID (%s)", sha1HashResult.getActualHash()) :
                        String.format("INVALID (expected %s, got %s)", sha1HashResult.getExpectedHash(), sha1HashResult.getActualHash()));
            }
        } catch (IOException e) {
            LOGGER.error("Command execution failed.", e);
        }
    }

    private String getKeyUserId(PGPPublicKey key) {
        Iterator<String> iterator = key.getUserIDs();
        return iterator.hasNext() ? iterator.next() : "unknown";
    }
}
