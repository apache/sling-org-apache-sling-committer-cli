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

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.sling.cli.impl.ComponentContextHelper;
import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component(service = HttpClientFactory.class)
public class HttpClientFactory {
    
    private static final String DEFAULT_JIRA_HOST = "jira.apache.org";
    private static final int DEFAULT_JIRA_PORT = 443;
    
    @Reference
    private CredentialsService credentialsService;
    
    private String jiraHost;
    private int jiraPort;
    
    protected void activate(ComponentContext ctx) {
        
        ComponentContextHelper helper = ComponentContextHelper.wrap(ctx);
        jiraHost = helper.getProperty("jira.host", DEFAULT_JIRA_HOST);
        jiraPort = helper.getProperty("jira.port", DEFAULT_JIRA_PORT);
    }

    public CloseableHttpClient newClient() {
        
        Credentials asf = credentialsService.getAsfCredentials();
        Credentials jira = credentialsService.getJiraCredentials();
        
        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(new AuthScope("repository.apache.org", 443), 
                new UsernamePasswordCredentials(asf.getUsername(), asf.getPassword()));
        credentialsProvider.setCredentials(new AuthScope("reporter.apache.org", 443), 
                new UsernamePasswordCredentials(asf.getUsername(), asf.getPassword()));
        credentialsProvider.setCredentials(new AuthScope(jiraHost, jiraPort), 
                new UsernamePasswordCredentials(jira.getUsername(), jira.getPassword()));
        
        return HttpClients.custom()
                .setDefaultCredentialsProvider(credentialsProvider)
                .build();
    }
}
