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
package org.apache.sling.cli.impl;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import org.junit.Test;

public class CredentialsServiceTest {

    private static final String[] VALID_PROPS = new String[] { "asf.username", "asf.password", "jira.username", "jira.password" };

    @Test(expected = IllegalStateException.class)
    public void noCredentialSourcesFound() {
        new CredentialsService().activate();
    }
    
    @Test
    public void credentialsFromSystemProps() {
        for ( String prop : VALID_PROPS ) {
            System.setProperty(prop, prop + ".val");
        }
        
        try {
            
            CredentialsService creds = new CredentialsService();
            creds.activate();
            
            assertThat(creds.getAsfCredentials().getUsername(), equalTo("asf.username.val"));
            assertThat(creds.getAsfCredentials().getPassword(), equalTo("asf.password.val"));
            
        } finally {
            for ( String prop : VALID_PROPS ) {
                System.clearProperty(prop);
            }
        }
    }
}
