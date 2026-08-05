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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.sling.cli.impl.Command;
import org.apache.sling.cli.impl.ExecutionMode;
import org.apache.sling.cli.impl.ci.CIStatusValidator;
import org.apache.sling.cli.impl.junit.LogCapture;
import org.apache.sling.cli.impl.nexus.Artifact;
import org.apache.sling.cli.impl.nexus.LocalRepository;
import org.apache.sling.cli.impl.nexus.RepositoryService;
import org.apache.sling.cli.impl.nexus.StagingRepository;
import org.apache.sling.cli.impl.pgp.HashValidator;
import org.apache.sling.cli.impl.pgp.PGPSignatureValidator;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Rule;
import org.junit.Test;
import picocli.CommandLine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class VerifyReleasesCommandTest {

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @Rule
    public final LogCapture logCapture = new LogCapture(VerifyReleasesCommand.class);

    @Test
    public void testEmptyRepositoryIsValid() throws Exception {
        // A downloaded repository with no artifacts runs no checks and is reported valid.
        LocalRepository localRepository = mock(LocalRepository.class);
        when(localRepository.getArtifacts()).thenReturn(Set.<Artifact>of());
        when(localRepository.getRootFolder()).thenReturn(Paths.get("/tmp"));

        RepositoryService repositoryService = mock(RepositoryService.class);
        StagingRepository stagingRepository = mock(StagingRepository.class);
        when(repositoryService.find(123)).thenReturn(stagingRepository);
        when(repositoryService.download(stagingRepository)).thenReturn(localRepository);

        osgiContext.registerService(RepositoryService.class, repositoryService);
        osgiContext.registerService(PGPSignatureValidator.class, mock(PGPSignatureValidator.class));
        osgiContext.registerService(CIStatusValidator.class, mock(CIStatusValidator.class));
        osgiContext.registerService(HashValidator.class, mock(HashValidator.class));

        Command command = createCommand(123, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.OK, (int) command.call());
        assertTrue(logCapture.containsMessage("VALID (0 checks executed)"));
    }

    @Test
    public void testInvalidSignatureFailsVerification() throws Exception {
        // One artifact whose signature, SHA-1 and MD5 all fail -> command reports INVALID.
        StagingRepository stagingRepository = mock(StagingRepository.class);
        Artifact jar =
                new Artifact(stagingRepository, "org.apache.sling", "org.apache.sling.cli.test", "1.0.0", null, "jar");
        LocalRepository localRepository = mock(LocalRepository.class);
        when(localRepository.getArtifacts()).thenReturn(Set.of(jar));
        when(localRepository.getRootFolder()).thenReturn(Paths.get("/tmp"));

        RepositoryService repositoryService = mock(RepositoryService.class);
        when(repositoryService.find(123)).thenReturn(stagingRepository);
        when(repositoryService.download(stagingRepository)).thenReturn(localRepository);

        PGPSignatureValidator pgp = mock(PGPSignatureValidator.class);
        PGPSignatureValidator.ValidationResult pgpResult = mock(PGPSignatureValidator.ValidationResult.class);
        when(pgpResult.isValid()).thenReturn(false);
        when(pgp.verify(any(Path.class), any(Path.class))).thenReturn(pgpResult);

        HashValidator hashValidator = mock(HashValidator.class);
        HashValidator.ValidationResult hashResult = mock(HashValidator.ValidationResult.class);
        when(hashResult.isValid()).thenReturn(false);
        when(hashResult.getExpectedHash()).thenReturn("expected");
        when(hashResult.getActualHash()).thenReturn("actual");
        when(hashValidator.validate(any(Path.class), any(Path.class), any())).thenReturn(hashResult);

        osgiContext.registerService(RepositoryService.class, repositoryService);
        osgiContext.registerService(PGPSignatureValidator.class, pgp);
        osgiContext.registerService(CIStatusValidator.class, mock(CIStatusValidator.class));
        osgiContext.registerService(HashValidator.class, hashValidator);

        Command command = createCommand(123, ExecutionMode.DRY_RUN);
        assertEquals(CommandLine.ExitCode.USAGE, (int) command.call());
        assertTrue(logCapture.containsMessage("INVALID"));
    }

    private Command createCommand(int repositoryId, ExecutionMode executionMode) throws IllegalAccessException {
        VerifyReleasesCommand verifyReleasesCommand = spy(new VerifyReleasesCommand());
        FieldUtils.writeField(verifyReleasesCommand, "repositoryId", repositoryId, true);
        ReusableCLIOptions reusableCLIOptions = mock(ReusableCLIOptions.class);
        FieldUtils.writeField(reusableCLIOptions, "executionMode", executionMode, true);
        FieldUtils.writeField(verifyReleasesCommand, "reusableCLIOptions", reusableCLIOptions, true);
        osgiContext.registerInjectActivateService(verifyReleasesCommand);
        Command result = osgiContext.getService(Command.class);
        assertTrue(
                "Expected to retrieve the VerifyReleasesCommand from the mocked OSGi environment.",
                result instanceof VerifyReleasesCommand);
        return result;
    }
}
