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
package org.apache.sling.cli.impl.http;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.sling.cli.impl.ComponentContextHelper;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component(service = HttpClientFactory.class)
public class HttpClientFactory {
    
    private static final String DEFAULT_JIRA_HOST = "issues.apache.org";
    private static final int DEFAULT_JIRA_PORT = 443;

    private static final String DEFAULT_NEXUS_HOST = "repository.apache.org";
    private static final int DEFAULT_NEXUS_PORT = 443;
    
    @Reference
    private CredentialsService credentialsService;
    
    private String jiraHost;
    private int jiraPort;
    private String nexusHost;
    private int nexusPort;

    @Activate
    protected void activate(ComponentContext ctx) {
        ComponentContextHelper helper = ComponentContextHelper.wrap(ctx);
        jiraHost = helper.getProperty("jira.host", DEFAULT_JIRA_HOST);
        jiraPort = helper.getProperty("jira.port", DEFAULT_JIRA_PORT);
        nexusHost = helper.getProperty("nexus.host", DEFAULT_NEXUS_HOST);
        nexusPort = helper.getProperty("nexus.port", DEFAULT_NEXUS_PORT);
    }

    public CloseableHttpClient newClient() {
        final String[] urlHolder = new String[1];
        return HttpClients.custom()
                .setDefaultCredentialsProvider(newCredentialsProvider())
                .addInterceptorFirst(
                        (HttpRequestInterceptor) (request, context) ->
                        urlHolder[0] = ((HttpRequestWrapper) request).getOriginal().getRequestLine().getUri()
                )
                .addInterceptorFirst((HttpResponseInterceptor) (response, context) -> {
                    if (response.getStatusLine().getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
                        throw new IllegalStateException("Server returned a 401 status; please check your authentication details for " + urlHolder[0]);
                    }
                })
                .build();
    }

    private BasicCredentialsProvider newCredentialsProvider() {
        Credentials asf = credentialsService.getAsfCredentials();

        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(new AuthScope(nexusHost, nexusPort),
                new UsernamePasswordCredentials(asf.getUsername(), asf.getPassword()));
        credentialsProvider.setCredentials(new AuthScope("reporter.apache.org", 443), 
                new UsernamePasswordCredentials(asf.getUsername(), asf.getPassword()));
        credentialsProvider.setCredentials(new AuthScope(jiraHost, jiraPort), 
                new UsernamePasswordCredentials(asf.getUsername(), asf.getPassword()));
        return credentialsProvider;
    }
    
    public HttpClientContext newPreemptiveAuthenticationContext() {
        
        AuthCache authCache = new BasicAuthCache();
        authCache.put(new HttpHost(jiraHost, jiraPort, "https"), new BasicScheme());
        
        HttpClientContext ctx = HttpClientContext.create();
        ctx.setAuthCache(authCache);
        ctx.setCredentialsProvider(newCredentialsProvider());
        
        return ctx;
    }
    
}
