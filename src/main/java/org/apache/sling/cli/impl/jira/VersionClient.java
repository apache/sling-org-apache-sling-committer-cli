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
package org.apache.sling.cli.impl.jira;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.ComponentContextHelper;
import org.apache.sling.cli.impl.DateProvider;
import org.apache.sling.cli.impl.http.HttpClientFactory;
import org.apache.sling.cli.impl.release.Release;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.util.promise.FailedPromisesException;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.PromiseFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Access the ASF <em>Jira</em> instance and looks up project version data.
 */
@Component(service = VersionClient.class)
public class VersionClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(VersionClient.class);

    private static final String PROJECT_KEY = "SLING";
    private static final String DEFAULT_JIRA_URL = "https://issues.apache.org/jira";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String ISSUE_PATH = "issue/";
    private static final String FIX_VERSIONS_FIELD = "fixVersions";

    private final PromiseFactory promiseFactory = new PromiseFactory(null, null);

    @Reference
    private HttpClientFactory httpClientFactory;

    @Reference
    private DateProvider dateProvider;

    private String jiraRESTAPIEntrypoint;
    private String jiraURL;

    @Activate
    protected void activate(ComponentContext ctx) {
        ComponentContextHelper helper = ComponentContextHelper.wrap(ctx);
        jiraURL = helper.getProperty("jira.url", DEFAULT_JIRA_URL);
        jiraRESTAPIEntrypoint = jiraURL + "/rest/api/2/";
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
            version = findVersion(v -> release.getName().equals(v.getName()), client)
                    .orElseThrow(() -> new IllegalArgumentException("No version found with name " + release.getName()));
            populateRelatedIssuesCount(client, version);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return version;
    }

    /**
     * Finds a version that is the successor of the version of the specified
     * <code>release</code>
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
     */
    public Version findSuccessorVersion(Release release) {
        Version version;

        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            Optional<Version> opt = findVersion(
                    v -> isFollowingVersion(Release.fromString(v.getName()).get(0), release), client);
            if (opt.isEmpty()) return null;
            version = opt.get();
            populateRelatedIssuesCount(client, version);
        } catch (IOException e) {
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
        try (JsonWriter jw = new Gson().newJsonWriter(w)) {
            jw.beginObject();
            jw.name("name").value(versionName);
            jw.name("project").value(PROJECT_KEY);
            jw.endObject();
        }

        HttpPost post = newPost("version");
        post.setEntity(new StringEntity(w.toString()));

        try (CloseableHttpClient client = httpClientFactory.newClient()) {
            try (CloseableHttpResponse response =
                    client.execute(post, httpClientFactory.newPreemptiveAuthenticationContext())) {
                try (InputStream content = response.getEntity().getContent();
                        InputStreamReader reader = new InputStreamReader(content)) {

                    if (response.getStatusLine().getStatusCode() != 201) {
                        throw newException(response, reader);
                    }
                }
            }
        }
    }

    private HttpGet newGet(String suffix) {
        HttpGet get = new HttpGet(jiraRESTAPIEntrypoint + suffix);
        get.addHeader(HttpHeaders.ACCEPT, CONTENT_TYPE_JSON);
        return get;
    }

    private HttpPost newPost(String suffix) {
        HttpPost post = new HttpPost(jiraRESTAPIEntrypoint + suffix);
        post.addHeader(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE_JSON);
        post.addHeader(HttpHeaders.ACCEPT, CONTENT_TYPE_JSON);
        return post;
    }

    private HttpPut newPut(String suffix) {
        HttpPut put = new HttpPut(jiraRESTAPIEntrypoint + suffix);
        put.addHeader(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE_JSON);
        put.addHeader(HttpHeaders.ACCEPT, CONTENT_TYPE_JSON);
        return put;
    }

    public List<Issue> findUnresolvedIssues(Release release) throws IOException {
        return findIssues(release).stream()
                .filter(issue -> issue.getResolution() == null)
                .collect(Collectors.toList());
    }

    public List<Issue> findFixedIssues(Release release) throws IOException {
        return findIssues(release).stream()
                .filter(issue -> issue.getResolution() != null)
                .collect(Collectors.toList());
    }

    /**
     * Finds issues that were tagged with the release's fix version <em>after</em> {@code stagedAt}.
     *
     * <p>Such issues cannot be part of a release whose artifacts were already staged at that point; a
     * common cause is a committer tagging an issue with a fix version that was already staged and out
     * for a vote (see SLING-13260).</p>
     *
     * @param release the release whose fix version is checked
     * @param stagedAt the moment the release artifacts were staged
     * @return the issues tagged with the fix version after {@code stagedAt}, never {@code null}
     * @throws IOException in case of any errors talking to JIRA
     */
    public List<Issue> findIssuesFixVersionedAfter(Release release, Instant stagedAt) throws IOException {
        return findIssuesFixVersionedAfter(release, findIssues(release), stagedAt);
    }

    private List<Issue> findIssuesFixVersionedAfter(Release release, List<Issue> issues, Instant stagedAt)
            throws IOException {
        List<Issue> late = new ArrayList<>();
        for (Issue issue : issues) {
            if (issue.getResolution() == null) {
                // unresolved issues are moved to the next version rather than closed, so they are not a concern
                continue;
            }
            Instant addedAt = findFixVersionAddedDate(issue, release.getName());
            if (addedAt != null && addedAt.isAfter(stagedAt)) {
                late.add(issue);
            }
        }
        return late;
    }

    /**
     * Returns the most recent moment the given fix version was added to the issue, according to its
     * change history, or {@code null} if that cannot be determined.
     */
    private Instant findFixVersionAddedDate(Issue issue, String versionName) throws IOException {
        HttpGet get = newGet(ISSUE_PATH + issue.getKey());
        try {
            URIBuilder builder = new URIBuilder(get.getURI());
            builder.addParameter("expand", "changelog");
            builder.addParameter("fields", ""); // only the changelog is needed
            get.setURI(builder.build());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }

        try (CloseableHttpClient client = httpClientFactory.newClient();
                CloseableHttpResponse response = client.execute(get);
                InputStream content = response.getEntity().getContent();
                InputStreamReader reader = new InputStreamReader(content)) {
            if (response.getStatusLine().getStatusCode() != 200) {
                throw newException(response, reader);
            }
            Changelog changelog = new Gson().fromJson(reader, IssueChangelog.class).changelog;
            return latestFixVersionChange(changelog, versionName);
        }
    }

    /**
     * Scans a Jira changelog for the most recent moment {@code versionName} was added to the issue's fix
     * versions, or {@code null} if the changelog is empty or records no such change.
     */
    private static Instant latestFixVersionChange(Changelog changelog, String versionName) {
        if (changelog == null || changelog.histories == null) {
            return null;
        }
        Instant latest = null;
        for (History history : changelog.histories) {
            Instant when = fixVersionChangeInstant(history, versionName);
            if (when != null && (latest == null || when.isAfter(latest))) {
                latest = when;
            }
        }
        return latest;
    }

    /** The timestamp of {@code history} when it records adding {@code versionName} to the fix versions, else null. */
    private static Instant fixVersionChangeInstant(History history, String versionName) {
        if (history.items == null || history.created == null) {
            return null;
        }
        for (HistoryItem item : history.items) {
            if (isFixVersionAddition(item, versionName)) {
                return OffsetDateTime.parse(history.created, JIRA_TIMESTAMP).toInstant();
            }
        }
        return null;
    }

    private static boolean isFixVersionAddition(HistoryItem item, String versionName) {
        boolean isFixVersionChange = FIX_VERSIONS_FIELD.equals(item.fieldId)
                || (item.field != null && item.field.equalsIgnoreCase("Fix Version"));
        return isFixVersionChange && versionName.equals(item.toString);
    }

    // JIRA change-history timestamps, e.g. "2024-01-15T10:30:00.000+0000"
    private static final DateTimeFormatter JIRA_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    static class IssueChangelog {
        private Changelog changelog;
    }

    static class Changelog {
        private List<History> histories;
    }

    static class History {
        private String created;
        private List<HistoryItem> items;
    }

    static class HistoryItem {
        private String field;
        private String fieldId;

        @SerializedName("toString")
        private String toString;
    }

    private void closeIssues(List<Issue> issues) throws Exception {
        List<Promise<Issue>> closedIssues = new ArrayList<>();
        for (Issue issue : issues) {
            if (!"Closed".equals(issue.getStatus())) {
                closedIssues.add(getCloseTransition(issue)
                        .then(closeTransition -> closeIssue(issue, closeTransition.getValue())));
            }
        }
        Promise<List<Issue>> closedFixedIssues = promiseFactory.all(closedIssues);
        Throwable failed = closedFixedIssues.getFailure();
        if (failed != null) {
            if (failed instanceof FailedPromisesException) {
                FailedPromisesException failedPromisesException = (FailedPromisesException) failed;
                StringBuilder failureMessages = new StringBuilder();
                for (Promise<?> promise : failedPromisesException.getFailedPromises()) {
                    failureMessages.append(promise.getFailure().getMessage()).append("\n");
                }
                throw new IOException("Unable to close the following issues:\n" + failureMessages.toString());
            } else {
                throw new Exception(failed);
            }
        }
    }

    public void release(Release release) throws Exception {
        release(release, null);
    }

    /**
     * Marks the JIRA version matching the {@code release} as released and closes all its fixed issues.
     *
     * <p>When {@code stagedAt} is provided (the moment the release artifacts were staged in Nexus), this
     * first guards against issues that acquired the release's fix version <em>after</em> the artifacts
     * were staged: such issues cannot physically be part of the release, so closing them under this
     * version would be wrong. If any are found the release is aborted before anything is closed, so a
     * human can correct the fix-version tagging. See SLING-13260.</p>
     *
     * @param release the release to mark as released
     * @param stagedAt the moment the artifacts were staged, or {@code null} to skip the staleness guard
     * @throws Exception on any error, or when unresolved / late-tagged issues are found
     */
    public void release(Release release, Instant stagedAt) throws Exception {
        List<Issue> issues = findIssues(release);
        List<Issue> unresolvedIssues = new ArrayList<>();
        issues.forEach(issue -> {
            if (issue.getResolution() == null) {
                unresolvedIssues.add(issue);
            }
        });
        if (unresolvedIssues.isEmpty()) {
            if (stagedAt != null) {
                List<Issue> lateIssues = findIssuesFixVersionedAfter(release, issues, stagedAt);
                if (!lateIssues.isEmpty()) {
                    String report = lateIssues.stream()
                            .map(issue -> String.format("%s/browse/%s", jiraURL, issue.getKey()))
                            .collect(Collectors.joining(System.lineSeparator()));
                    throw new IllegalStateException(String.format(
                            "The following issues were tagged with fix version \"%s\" after the release artifacts were "
                                    + "staged (%s), so they cannot be part of the release and must not be closed under "
                                    + "it. Re-tag them to the correct fix version before finalizing:%n%s",
                            release.getName(), stagedAt, report));
                }
            }
            closeIssues(issues);
            Version version = find(release);
            if (!version.isReleased()) {
                HttpPut put = newPut("version/" + version.getId());
                StringWriter w = new StringWriter();
                try (JsonWriter jw = new Gson().newJsonWriter(w)) {
                    jw.beginObject()
                            .name("released")
                            .value(true)
                            .name("releaseDate")
                            .value(dateProvider.getCurrentDateForJiraRelease())
                            .endObject();
                }
                put.setEntity(new StringEntity(w.toString()));
                try (CloseableHttpClient client = httpClientFactory.newClient()) {
                    try (CloseableHttpResponse response =
                            client.execute(put, httpClientFactory.newPreemptiveAuthenticationContext())) {
                        int statusCode = response.getStatusLine().getStatusCode();
                        if (statusCode != 200) {
                            throw new IOException(String.format(
                                    "Unable to mark %s as released. Got status code %d.",
                                    release.getFullName(), statusCode));
                        }
                    }
                }
            } else {
                LOGGER.info("Version {} was already released on {}.", version.getName(), version.getReleaseDate());
            }
        } else {
            String report = unresolvedIssues.stream()
                    .map(issue -> String.format("%s/browse/%s", jiraURL, issue.getKey()))
                    .collect(Collectors.joining(System.lineSeparator()));
            throw new IllegalStateException("The following issues are not fixed:\n" + report);
        }
    }

    private List<Issue> findIssues(Release release) throws IOException {
        try {
            HttpGet get = newGet("search");
            URIBuilder builder = new URIBuilder(get.getURI());
            builder.addParameter(
                    "jql", String.format("project = %s AND fixVersion = \"%s\"", PROJECT_KEY, release.getName()));
            builder.addParameter("fields", "summary,status,resolution");
            get.setURI(builder.build());

            try (CloseableHttpClient client = httpClientFactory.newClient()) {
                try (CloseableHttpResponse response = client.execute(get)) {
                    try (InputStream content = response.getEntity().getContent();
                            InputStreamReader reader = new InputStreamReader(content)) {

                        if (response.getStatusLine().getStatusCode() != 200) {
                            throw newException(response, reader);
                        }

                        Gson gson = new Gson();
                        return gson.fromJson(reader, IssueResponse.class).getIssues();
                    }
                }
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private IOException newException(CloseableHttpResponse response, InputStreamReader reader) {

        StringBuilder message = new StringBuilder();
        message.append("Status line : ").append(response.getStatusLine());

        try {
            Gson gson = new Gson();
            ErrorResponse errors = gson.fromJson(reader, ErrorResponse.class);
            if (errors != null) {
                if (!errors.getErrorMessages().isEmpty())
                    message.append(". Error messages: ").append(errors.getErrorMessages());

                if (!errors.getErrors().isEmpty())
                    errors.getErrors().forEach((key, value) -> message.append(". Error for ")
                            .append(key)
                            .append(" : ")
                            .append(value));
            }
        } catch (JsonIOException | JsonSyntaxException e) {
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
                if (response.getStatusLine().getStatusCode() != 200) throw newException(response, reader);

                Gson gson = new Gson();
                Type collectionType =
                        TypeToken.getParameterized(List.class, Version.class).getType();
                List<Version> versions = gson.fromJson(reader, collectionType);
                return versions.stream()
                        .filter(v -> v.getName().length() > 1) // avoid old '3' release
                        .filter(matcher)
                        .min(VersionClient::compare);
            }
        }
    }

    private void populateRelatedIssuesCount(CloseableHttpClient client, Version version) throws IOException {

        HttpGet get = newGet("version/" + version.getId() + "/relatedIssueCounts");
        try (CloseableHttpResponse response = client.execute(get)) {
            try (InputStream content = response.getEntity().getContent();
                    InputStreamReader reader = new InputStreamReader(content)) {
                if (response.getStatusLine().getStatusCode() != 200) throw newException(response, reader);

                Gson gson = new Gson();
                VersionRelatedIssuesCount issuesCount = gson.fromJson(reader, VersionRelatedIssuesCount.class);

                version.setRelatedIssuesCount(issuesCount.getIssuesFixedCount());
            }
        }
    }

    private boolean isFollowingVersion(Release base, Release candidate) {
        return base.getComponent().equals(candidate.getComponent())
                && new org.osgi.framework.Version(base.getVersion())
                                .compareTo(new org.osgi.framework.Version(candidate.getVersion()))
                        > 0;
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

        @SuppressWarnings("unused")
        public void setIssuesFixedCount(int issuesFixedCount) {
            this.issuesFixedCount = issuesFixedCount;
        }
    }

    public void moveIssuesToNewVersion(Version oldVersion, Version newVersion, List<Issue> issues) {
        issues.forEach(i -> moveIssueToNewVersion(oldVersion, newVersion, i));
    }

    private void moveIssueToNewVersion(Version oldVersion, Version newVersion, Issue issue) {
        try {
            StringWriter w = new StringWriter();

            IssueUpdate update = new IssueUpdate();
            update.recordAdd(FIX_VERSIONS_FIELD, newVersion.getName());
            update.recordRemove(FIX_VERSIONS_FIELD, oldVersion.getName());
            Gson gson = new Gson();
            gson.toJson(update, w);

            HttpPut put = newPut(ISSUE_PATH + issue.getKey());
            put.setEntity(new StringEntity(w.toString(), StandardCharsets.UTF_8));

            try (CloseableHttpClient client = httpClientFactory.newClient()) {
                try (CloseableHttpResponse response =
                        client.execute(put, httpClientFactory.newPreemptiveAuthenticationContext())) {
                    if (response.getStatusLine().getStatusCode() != 204) {
                        try (InputStream content = response.getEntity().getContent();
                                InputStreamReader reader = new InputStreamReader(content)) {
                            throw newException(response, reader);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Promise<Transition> getCloseTransition(Issue issue) {
        HttpGet get = newGet(ISSUE_PATH + issue.getId() + "/transitions");
        try {
            try (CloseableHttpClient client = httpClientFactory.newClient()) {
                try (CloseableHttpResponse getResponse =
                        client.execute(get, httpClientFactory.newPreemptiveAuthenticationContext())) {
                    try (InputStream getContent = getResponse.getEntity().getContent();
                            InputStreamReader getReader = new InputStreamReader(getContent)) {
                        if (getResponse.getStatusLine().getStatusCode() != 200) {
                            throw newException(getResponse, getReader);
                        }
                        Gson gson = new Gson();
                        List<Transition> transitions = gson.fromJson(getReader, TransitionsResponse.class)
                                .getTransitions();
                        Optional<Transition> transition = transitions.stream()
                                .filter(t -> "Close Issue".equals(t.getName()))
                                .findFirst();
                        if (transition.isPresent()) {
                            return promiseFactory.resolved(transition.get());
                        } else {
                            return promiseFactory.failed(new IllegalStateException(String.format(
                                    "Issue %s/browse/%s cannot be closed - missing Close " + "transition.",
                                    jiraURL, issue.getKey())));
                        }
                    }
                }
            }
        } catch (Exception e) {
            return promiseFactory.failed(e);
        }
    }

    private Promise<Issue> closeIssue(Issue issue, Transition closeTransition) {
        HttpPost post = newPost(ISSUE_PATH + issue.getId() + "/transitions");
        StringWriter w = new StringWriter();
        try (JsonWriter jw = new Gson().newJsonWriter(w)) {
            jw.beginObject()
                    .name("transition")
                    .beginObject()
                    .name("id")
                    .value(closeTransition.getId())
                    .endObject()
                    .endObject();
            post.setEntity(new StringEntity(w.toString()));
            try (CloseableHttpClient client = httpClientFactory.newClient()) {
                try (CloseableHttpResponse postResponse =
                        client.execute(post, httpClientFactory.newPreemptiveAuthenticationContext())) {
                    if (postResponse.getStatusLine().getStatusCode() == 204) {
                        return promiseFactory.resolved(issue);
                    } else {
                        return promiseFactory.failed(new RuntimeException(String.format(
                                "Unable to close issue %s/browse/%s - got status code %d.",
                                jiraURL,
                                issue.getKey(),
                                postResponse.getStatusLine().getStatusCode())));
                    }
                }
            }
        } catch (IOException e) {
            return promiseFactory.failed(e);
        }
    }
}
