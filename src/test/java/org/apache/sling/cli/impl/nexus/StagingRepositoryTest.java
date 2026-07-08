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
package org.apache.sling.cli.impl.nexus;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StagingRepositoryTest {

    @Test
    public void testAccessors() {
        StagingRepository repository = new StagingRepository();
        repository.setDescription("Apache Sling Foo 1.0.0");
        repository.setRepositoryId("orgapachesling-7");
        repository.setRepositoryURI("https://repository.apache.org/content/repositories/orgapachesling-7");
        repository.setUserId("johndoe");
        repository.setType(StagingRepository.Status.closed);

        assertEquals("Apache Sling Foo 1.0.0", repository.getDescription());
        assertEquals("orgapachesling-7", repository.getRepositoryId());
        assertEquals(
                "https://repository.apache.org/content/repositories/orgapachesling-7", repository.getRepositoryURI());
        assertEquals("johndoe", repository.getUserId());
        assertEquals(StagingRepository.Status.closed, repository.getType());
    }
}
