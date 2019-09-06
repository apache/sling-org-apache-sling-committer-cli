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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

@Component(service = PGPSignaturesValidator.class)
public class PGPSignaturesValidator {

    private static final String KEYS_FILE = "/tmp/sling-keys.asc";
    private PGPPublicKeyRingCollection keyRing;

    public SignatureVerificationResult verify(Path artifact, Path signature) {
        try (
                InputStream fileStream = Files.newInputStream(artifact) ;
                InputStream signatureStream = Files.newInputStream(signature)
        ) {
            InputStream sigInputStream = PGPUtil.getDecoderStream(signatureStream);
            PGPObjectFactory pgpObjectFactory = new PGPObjectFactory(sigInputStream, new BcKeyFingerprintCalculator());
            PGPSignatureList sigList = (PGPSignatureList) pgpObjectFactory.nextObject();
            PGPSignature pgpSignature = sigList.get(0);
            PGPPublicKey key = keyRing.getPublicKey(pgpSignature.getKeyID());
            if (key == null) {
                throw new IllegalStateException(String.format("Signature %s was not generated with any of the known keys.",
                        signature.getFileName()));
            }
            pgpSignature.init(new BcPGPContentVerifierBuilderProvider(), key);
            byte[] buff = new byte[1024];
            int read;
            while ((read = fileStream.read(buff)) != -1) {
                pgpSignature.update(buff, 0, read);
            }
            fileStream.close();
            return new SignatureVerificationResult(pgpSignature.verify(), key);
        } catch (PGPException | IOException e) {
            throw new IllegalStateException(String.format("Unable to verify signature %s.", signature.getFileName()), e);
        }
    }

    @Activate
    private void readKeyRing() {
        Path keysFile = Paths.get(KEYS_FILE);
        if (Files.notExists(keysFile)) {
            throw new IllegalStateException(String.format("Sling keys file does not exist at %s.", KEYS_FILE));
        }
        try (InputStream in = Files.newInputStream(keysFile)) {
            InputStream bouncyIn = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(in);
            if (bouncyIn instanceof ArmoredInputStream) {
                ArmoredInputStream as = (ArmoredInputStream) bouncyIn;
                List<PGPPublicKeyRing> keyRings = new ArrayList<>();
                while (!as.isEndOfStream()) {
                    PGPPublicKeyRingCollection collection = new PGPPublicKeyRingCollection(as, new JcaKeyFingerprintCalculator());
                    Iterator<PGPPublicKeyRing> readKeyRings = collection.getKeyRings();
                    while (readKeyRings.hasNext()) {
                        PGPPublicKeyRing keyRing = readKeyRings.next();
                        keyRings.add(keyRing);
                    }
                }
                if (!keyRings.isEmpty()) {
                    keyRing = new PGPPublicKeyRingCollection(keyRings);
                } else {
                    throw new IllegalStateException(String.format("Sling keys file from %s does not contain any keys.", KEYS_FILE));
                }
            }
        } catch (IOException | PGPException e) {
            throw new IllegalStateException(String.format("Cannot read Sling keys file at %s.", KEYS_FILE), e);
        }
    }
}
