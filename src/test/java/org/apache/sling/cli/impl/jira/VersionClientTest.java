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
package org.apache.sling.cli.impl.jira;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.release.Release;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class VersionClientTest {

    private static final Map<String, String> SYSTEM_PROPS = new HashMap<>();
    static {
        SYSTEM_PROPS.put("asf.username", "asf-user");
        SYSTEM_PROPS.put("asf.password", "asf-password");
        SYSTEM_PROPS.put("jira.username", "jira-user");
        SYSTEM_PROPS.put("jira.password", "jira-password");
    }
    
    @Rule
    public final OsgiContext context = new OsgiContext();
    
    @Rule
    public final SystemPropertiesRule sysProps = new SystemPropertiesRule(SYSTEM_PROPS);
    
    @Rule
    public final MockJira mockJira = new MockJira();
    
    private VersionClient versionClient;
    
    @Before
    public void prepareDependencies() throws ReflectiveOperationException {
        
        context.registerInjectActivateService(new CredentialsService());
        context.registerInjectActivateService(new HttpClientFactory(), "jira.host", "localhost", "jira.port", mockJira.getBoundPort());
        versionClient = context.registerInjectActivateService(new VersionClient(), "jira.url.prefix", "http://localhost:" + mockJira.getBoundPort() + "/jira/rest/api/2/");
    }
    
    @Test
    public void findMatchingVersion() {
        
        Version version = versionClient.find(Release.fromString("XSS Protection API 1.0.2").get(0));
        
        assertThat("version", version, notNullValue());
        assertThat("version.name", version.getName(), equalTo("XSS Protection API 1.0.2"));
        assertThat("version.id", version.getId(), equalTo(12329667));
        assertThat("version.issuesFixedCount", version.getIssuesFixedCount(), equalTo(1)); 
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingVersionNotFound() {
        
        versionClient.find(Release.fromString("XSS Protection API 1.0.3").get(0));
    }
    
    @Test
    public void findSuccessorVersion() {
        Version successor = versionClient.findSuccessorVersion(Release.fromString("XSS Protection API 1.0.2").get(0));
        
        assertThat("successor", successor, notNullValue());
        assertThat("successor.name", successor.getName(), equalTo("XSS Protection API 1.0.4"));
    }

    @Test
    public void noSuccessorVersion() {
        Version successor = versionClient.findSuccessorVersion(Release.fromString("XSS Protection API 1.0.16").get(0));
        
        assertThat("successor", successor, nullValue());
    }
}
