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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.sling.cli.impl.release.Release;
import org.osgi.service.component.annotations.Component;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Access the ASF <em>Jira</em> instance and looks up project version data.
 */
@Component(service = VersionFinder.class)
public class VersionFinder {

    /**
     * Finds a Jira version which matches the specified release
     * 
     * @param release the release
     * @return the version
     * @throws IllegalArgumentException when no matching Jira release is found
     */
    public Version find(Release release) {
        Version version;
        
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            version = findVersion( v -> release.getName().equals(v.getName()), client)
                    .orElseThrow( () -> new IllegalArgumentException("No version found with name " + release.getName()));
            populateRelatedIssuesCount(client, version);
        } catch ( IOException e ) {
            throw new RuntimeException(e);
        }
        
        return version;
    }

    /**
     * Finds a version that is the successor of the version of the specified
     * <tt>release</tt>
     * 
     * <p>
     * A successor has the same base name but a higher version. For instance, the
     * <em>XSS Protection API 2.1.6</em> is succeeded by <em>XSS Protection API
     * 2.1.8</em>.
     * </p>
     * 
     * <p>
     * If multiple successors are found the one which is closest in terms of
     * versioning is returned.
     * </p>
     * 
     * @param release the release to find a successor for
     * @return the successor version, possibly <code>null</code>
     * @throws IOException in case of communication errors with Jira
     */
    public Version findSuccessorVersion(Release release) {
        Version version;
        
        try ( CloseableHttpClient client = HttpClients.createDefault() ) {
            Optional<Version> opt = findVersion ( 
                    v -> {
                        Release releaseFromVersion = Release.fromString(v.getName()).get(0);
                        return 
                            releaseFromVersion.getComponent().equals(release.getComponent())
                                && new org.osgi.framework.Version(releaseFromVersion.getVersion()).compareTo(new org.osgi.framework.Version(release.getVersion())) > 0;
                    },client);
            if ( !opt.isPresent() )
                return null;
            version = opt.get();
            populateRelatedIssuesCount(client, version);
        } catch ( IOException e ) {
            throw new RuntimeException(e);
        }
        
        return version;
    }

    private Optional<Version> findVersion(Predicate<Version> matcher, CloseableHttpClient client) throws IOException {
        
        return doWithJiraVersions(client, reader -> {
            Gson gson = new Gson();
            Type collectionType = TypeToken.getParameterized(List.class, Version.class).getType();
            List<Version> versions = gson.fromJson(reader, collectionType);
            return versions.stream()
                    .filter( v -> v.getName().length() > 1) // avoid old '3' release
                    .filter(matcher)
                    .sorted(VersionFinder::compare)
                    .findFirst();
        });
    }
    
    protected <T> T doWithJiraVersions(CloseableHttpClient client, Function<InputStreamReader, T> parserCallback) throws IOException {
        HttpGet get = newGet("project/SLING/versions");
        try (CloseableHttpResponse response = client.execute(get)) {
            try (InputStream content = response.getEntity().getContent();
                    InputStreamReader reader = new InputStreamReader(content)) {
                if (response.getStatusLine().getStatusCode() != 200)
                    throw new IOException("Status line : " + response.getStatusLine());
                
                return parserCallback.apply(reader);
            }
        }
    }
    
    protected <T> T doWithRelatedIssueCounts(CloseableHttpClient client, Version version, Function<InputStreamReader, T> parserCallback) throws IOException {
        
        HttpGet get = newGet("version/" + version.getId() +"/relatedIssueCounts");
        try (CloseableHttpResponse response = client.execute(get)) {
            try (InputStream content = response.getEntity().getContent();
                    InputStreamReader reader = new InputStreamReader(content)) {
                if (response.getStatusLine().getStatusCode() != 200)
                    throw new IOException("Status line : " + response.getStatusLine());
                return parserCallback.apply(reader);
            }
        }
    }
    
    private HttpGet newGet(String suffix) {
        HttpGet get = new HttpGet("https://issues.apache.org/jira/rest/api/2/" + suffix);
        get.addHeader("Accept", "application/json");
        return get;
    }
    
    private void populateRelatedIssuesCount(CloseableHttpClient client, Version version) throws IOException {
        
        doWithRelatedIssueCounts(client, version, reader ->  {
            Gson gson = new Gson();
            VersionRelatedIssuesCount issuesCount = gson.fromJson(reader, VersionRelatedIssuesCount.class);
            
            version.setRelatedIssuesCount(issuesCount.getIssuesFixedCount());

            return null;
        });
    }
    
    private static int compare(Version v1, Version v2) {
        // version names will never map to multiple release names
        Release r1 = Release.fromString(v1.getName()).get(0);
        Release r2 = Release.fromString(v2.getName()).get(0);
        
        org.osgi.framework.Version ver1 = new org.osgi.framework.Version(r1.getVersion());
        org.osgi.framework.Version ver2 = new org.osgi.framework.Version(r2.getVersion());
        
        return ver1.compareTo(ver2);
    }

    static class VersionRelatedIssuesCount {

        private int issuesFixedCount;

        public int getIssuesFixedCount() {
            return issuesFixedCount;
        }

        public void setIssuesFixedCount(int issuesFixedCount) {
            this.issuesFixedCount = issuesFixedCount;
        }
    }
}
