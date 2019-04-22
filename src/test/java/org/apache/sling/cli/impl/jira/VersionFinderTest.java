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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.function.Function;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.release.Release;
import org.junit.Test;

public class VersionFinderTest {

    private VersionClient finder = new StubVersionFinder();
    
    @Test
    public void findMatchingVersion() {
        
        finder = new StubVersionFinder();
        Version version = finder.find(Release.fromString("XSS Protection API 1.0.2").get(0));
        
        assertThat("version", version, notNullValue());
        assertThat("version.name", version.getName(), equalTo("XSS Protection API 1.0.2"));
        assertThat("version.id", version.getId(), equalTo(12329667));
        assertThat("version.issuesFixedCount", version.getIssuesFixedCount(), equalTo(1)); 
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingVersionNotFound() {
        
        finder.find(Release.fromString("XSS Protection API 1.0.3").get(0));
    }
    
    @Test
    public void findSuccessorVersion() {
        Version successor = finder.findSuccessorVersion(Release.fromString("XSS Protection API 1.0.2").get(0));
        
        assertThat("successor", successor, notNullValue());
        assertThat("successor.name", successor.getName(), equalTo("XSS Protection API 1.0.4"));
    }

    @Test
    public void noSuccessorVersion() {
        Version successor = finder.findSuccessorVersion(Release.fromString("XSS Protection API 1.0.16").get(0));
        
        assertThat("successor", successor, nullValue());
    }
    
    private static final class StubVersionFinder extends VersionClient {
        @Override
        protected <T> T doWithJiraVersions(CloseableHttpClient client, Function<InputStreamReader, T> parserCallback)
                throws IOException {
            
            try ( InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream("/jira/versions.json")) ) {
                return parserCallback.apply(reader);
            }
        }
        
        @Override
        protected <T> T doWithRelatedIssueCounts(CloseableHttpClient client, Version version,
                Function<InputStreamReader, T> parserCallback) throws IOException {
            
            InputStream stream = getClass().getResourceAsStream("/jira/relatedIssueCounts/" + version.getId()+".json");
            if ( stream == null )
                throw new IllegalArgumentException("No related issues count for version " + version.getId() + " (" + version.getName() + ")");
            
            try ( InputStreamReader reader = new InputStreamReader(stream) ) {
                return parserCallback.apply(reader);
            }
        }
    }
}
