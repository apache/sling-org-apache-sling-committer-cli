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
package org.apache.sling.cli.impl.pgp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.bouncycastle.util.encoders.Hex;
import org.osgi.service.component.annotations.Component;

@Component(service = HashValidator.class)
public class HashValidator {

    public ValidationResult validate(Path artifact, Path hash, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream artifactIS = Files.newInputStream(artifact)) {
                byte[] buff = new byte[4096];
                int read;
                while ((read = artifactIS.read(buff)) != -1) {
                    digest.update(buff, 0, read);
                }
                byte[] hashed = digest.digest();
                String actualHash = Hex.toHexString(hashed);
                String expectedHash = Files.readString(hash, StandardCharsets.US_ASCII);
                return new ValidationResult(actualHash.equalsIgnoreCase(expectedHash), expectedHash, actualHash);
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(String.format("Cannot validate %s hash.", algorithm), e);
        }
    }

    public static class ValidationResult {

        private final boolean valid;
        private final String expectedHash;
        private final String actualHash;

        ValidationResult(boolean valid, String expectedHash, String actualHash) {
            this.valid = valid;
            this.expectedHash = expectedHash;
            this.actualHash = actualHash;
        }

        public boolean isValid() {
            return valid;
        }

        public String getExpectedHash() {
            return expectedHash;
        }

        public String getActualHash() {
            return actualHash;
        }
    }
}
