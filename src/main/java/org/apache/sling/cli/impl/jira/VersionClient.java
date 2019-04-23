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
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.ComponentContextHelper;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.release.Release;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;

/**
 * Access the ASF <em>Jira</em> instance and looks up project version data.
 */
@Component(service = VersionClient.class)
public class VersionClient {
    
    private static final String PROJECT_KEY = "SLING";
    private static final String DEFAULT_JIRA_URL_PREFIX = "https://issues.apache.org/jira/rest/api/2/";
    private static final String CONTENT_TYPE_JSON = "application/json";
    
    @Reference
    private HttpClientFactory httpClientFactory;
    private String jiraUrlPrefix;
    
    protected void activate(ComponentContext ctx) {
        ComponentContextHelper helper = ComponentContextHelper.wrap(ctx);
        jiraUrlPrefix = helper.getProperty("jira.url.prefix", DEFAULT_JIRA_URL_PREFIX);
    }

    /**
     * Finds a Jira version which matches the specified release
     * 
     * @param release the release
     * @return the version
     * @throws IllegalArgumentException when no matching Jira release is found
     */
    public Version find(Release release) {
        Version version;
        
        try (CloseableHttpClient client = httpClientFactory.newClient()) {
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
        
        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            Optional<Version> opt = findVersion ( 
                    v -> isFollowingVersion(Release.fromString(v.getName()).get(0), release)
                    ,client);
            if ( !opt.isPresent() )
                return null;
            version = opt.get();
            populateRelatedIssuesCount(client, version);
        } catch ( IOException e ) {
            throw new RuntimeException(e);
        }
        
        return version;
    }
    
    /**
     * Creates a version with the specified name
     * 
     * <p>The version will be created for the {@value #PROJECT_KEY} project.</p>
     * 
     * @param versionName the name of the version
     * @throws IOException In case of any errors creating the version in Jira
     */
    public void create(String versionName) throws IOException {
        StringWriter w = new StringWriter();
        try ( JsonWriter jw = new Gson().newJsonWriter(w) ) {
            jw.beginObject();
            jw.name("name").value(versionName);
            jw.name("project").value(PROJECT_KEY);
            jw.endObject();
        }
        
        HttpPost post = new HttpPost(jiraUrlPrefix + "version");
        post.addHeader("Content-Type", CONTENT_TYPE_JSON);
        post.addHeader("Accept", CONTENT_TYPE_JSON);
        post.setEntity(new StringEntity(w.toString()));

        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            try (CloseableHttpResponse response = client.execute(post)) {
                try (InputStream content = response.getEntity().getContent();
                        InputStreamReader reader = new InputStreamReader(content)) {
                    
                    if (response.getStatusLine().getStatusCode() != 201) {
                        throw newException(response, reader);
                    }
                }
            }
        }
    }

    private IOException newException(CloseableHttpResponse response, InputStreamReader reader) throws IOException {
        
        StringBuilder message = new StringBuilder();
        message.append("Status line : " + response.getStatusLine());
        
        try {
            Gson gson = new Gson();
            ErrorResponse errors = gson.fromJson(reader, ErrorResponse.class);
            if ( !errors.getErrorMessages().isEmpty() )
                message.append(". Error messages: ")
                    .append(errors.getErrorMessages());
            
            if ( !errors.getErrors().isEmpty() )
                errors.getErrors().entrySet().stream()
                    .forEach( e -> message.append(". Error for "  + e.getKey() + " : " + e.getValue()));
            
        } catch ( JsonIOException | JsonSyntaxException e) {
            message.append(". Failed parsing response as JSON ( ")
                .append(e.getMessage())
                .append(" )");
        }
        
        return new IOException(message.toString());
    }

    private Optional<Version> findVersion(Predicate<Version> matcher, CloseableHttpClient client) throws IOException {
        
        HttpGet get = newGet("project/" + PROJECT_KEY + "/versions");
        try (CloseableHttpResponse response = client.execute(get)) {
            try (InputStream content = response.getEntity().getContent();
                    InputStreamReader reader = new InputStreamReader(content)) {
                if (response.getStatusLine().getStatusCode() != 200)
                    throw newException(response, reader);
                
                Gson gson = new Gson();
                Type collectionType = TypeToken.getParameterized(List.class, Version.class).getType();
                List<Version> versions = gson.fromJson(reader, collectionType);
                return versions.stream()
                        .filter( v -> v.getName().length() > 1) // avoid old '3' release
                        .filter(matcher)
                        .sorted(VersionClient::compare)
                        .findFirst();
            }
        }
    }
        
    private HttpGet newGet(String suffix) {
        HttpGet get = new HttpGet(jiraUrlPrefix + suffix);
        get.addHeader("Accept", CONTENT_TYPE_JSON);
        return get;
    }
    
    private void populateRelatedIssuesCount(CloseableHttpClient client, Version version) throws IOException {
        
        HttpGet get = newGet("version/" + version.getId() +"/relatedIssueCounts");
        try (CloseableHttpResponse response = client.execute(get)) {
            try (InputStream content = response.getEntity().getContent();
                    InputStreamReader reader = new InputStreamReader(content)) {
                if (response.getStatusLine().getStatusCode() != 200)
                    throw newException(response, reader);

                Gson gson = new Gson();
                VersionRelatedIssuesCount issuesCount = gson.fromJson(reader, VersionRelatedIssuesCount.class);
                
                version.setRelatedIssuesCount(issuesCount.getIssuesFixedCount());
            }
        }
    }

    private boolean isFollowingVersion(Release base, Release candidate) {
        return base.getComponent().equals(candidate.getComponent())
                && new org.osgi.framework.Version(base.getVersion())
                    .compareTo(new org.osgi.framework.Version(candidate.getVersion())) > 0;
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
