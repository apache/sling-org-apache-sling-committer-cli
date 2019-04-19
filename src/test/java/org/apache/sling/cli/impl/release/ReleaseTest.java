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
package org.apache.sling.cli.impl.release;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Ignore;
import org.junit.Test;

public class ReleaseTest {

    @Test
    public void fromRepositoryDescription() {
        
        List<Release> releases1 = Release.fromString("Apache Sling Resource Merger 1.3.10 RC1");
        List<Release> releases2 = Release.fromString("   Apache Sling Resource Merger    1.3.10   ");
        
        assertEquals(1, releases1.size());
        assertEquals(1, releases2.size());
        
        Release rel1 = releases1.get(0);
        Release rel2 = releases2.get(0);

        assertEquals("Resource Merger 1.3.10", rel1.getName());
        assertEquals("Apache Sling Resource Merger 1.3.10", rel1.getFullName());
        assertEquals("1.3.10", rel1.getVersion());
        assertEquals("Resource Merger", rel1.getComponent());

        assertEquals(rel1, rel2);
    }

    @Test
    public void fromRepositoryDescriptionWithMultipleArtifacts() {
        List<Release> releases = Release.fromString("Apache Sling Parent 35, Apache Sling Bundle Parent 35");
        assertEquals(2, releases.size());
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void noReleasesFailsFast() {
        Release.fromString("");
    }
    
    @Test
    public void releaseWithRCSuffixOnly() {
        List<Release> releases = Release.fromString("Apache Sling Resource Resolver 1.6.12 RC");
        
        assertEquals(1, releases.size());
        assertEquals("Apache Sling Resource Resolver 1.6.12", releases.get(0).getFullName());
    }

    @Test
    public void testReleaseParsingWithJIRAInfo() throws URISyntaxException, IOException {
        BufferedReader reader = new BufferedReader(new FileReader(new File(getClass().getResource("/jira_versions.txt").toURI())));
        reader.lines().forEach(line -> {
            if (!line.startsWith("#") && !"".equals(line)) {
                List<Release> jiraReleases = Release.fromString(line);
                assertEquals(1, jiraReleases.size());
                Release jiraRelease = jiraReleases.get(0);
                String releaseFullName = jiraRelease.getFullName();
                if (releaseFullName == null) {
                    fail("Failed to parse JIRA version: " + line);
                }
                int indexComponent = line.indexOf(jiraRelease.getComponent());
                int indexVersion = line.indexOf(jiraRelease.getVersion());
                assertTrue(indexComponent >= 0 && indexVersion > indexComponent);
            }
        });
        reader.close();
    }

    @Test
    public void nextVersion() {
        Release release = Release.fromString("Apache Sling Foo 1.0.2").get(0);
        Release next = release.next();
        
        assertEquals("Apache Sling Foo 1.0.4", next.getFullName());
    }
    
    @Test
    public void nextVersionWithSingleNumber() {
        Release release = Release.fromString("Apache Sling Bar 2").get(0);
        Release next = release.next();
        
        assertEquals("Apache Sling Bar 3", next.getFullName());
    }

}
