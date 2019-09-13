/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ~ Licensed to the Apache Software Foundation (ASF) under one
 ~ or more contributor license agreements.  See the NOTICE file
 ~ distributed with this work for additional information
 ~ regarding copyright ownership.  The ASF licenses this file
 ~ to you under the Apache License, Version 2.0 (the
 ~ "License"); you may not use this file except in compliance
 ~ with the License.  You may obtain a copy of the License at
 ~
 ~   http://www.apache.org/licenses/LICENSE-2.0
 ~
 ~ Unless required by applicable law or agreed to in writing,
 ~ software distributed under the License is distributed on an
 ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 ~ KIND, either express or implied.  See the License for the
 ~ specific language governing permissions and limitations
 ~ under the License.
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/
package org.apache.sling.cli.impl.nexus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.sling.cli.impl.http.HttpExchangeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;

public class QueryLuceneIndexHandler implements HttpExchangeHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryLuceneIndexHandler.class);

    @Override
    public boolean tryHandle(HttpExchange ex) throws IOException {
        if ( !ex.getRequestMethod().equals("GET") ||
                !ex.getRequestURI().getPath().equals("/service/local/lucene/search")) {
            return false;
        }
        String group = null;
        String repositoryId = null;
        List<NameValuePair> parameters = URLEncodedUtils.parse(ex.getRequestURI(), StandardCharsets.UTF_8);
        for (NameValuePair pair : parameters) {
            if ("g".equalsIgnoreCase(pair.getName())) {
                group = pair.getValue();
            }
            if ("repositoryId".equalsIgnoreCase(pair.getName())) {
                repositoryId = pair.getValue();
            }
        }
        if (StringUtils.isEmpty(group)) {
            LOGGER.warn("Expected a group (g) parameter. Skipping handler.");
            return false;
        }
        serveFileFromClasspath(ex, "/nexus/" + repositoryId + "/lucene.json");
        return true;
    }
}
