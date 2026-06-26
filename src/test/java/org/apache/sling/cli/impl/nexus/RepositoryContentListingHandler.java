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
package org.apache.sling.cli.impl.nexus;

import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;
import org.apache.sling.cli.impl.http.HttpExchangeHandler;

/**
 * Serves the Nexus repository content-browsing API used by
 * {@code RepositoryService#getReleasesFromContent}. Requests to
 * {@code /service/local/repositories/<id>/content/<path>} are mapped to classpath resources under
 * {@code /nexus-content/<id>/<path>}; directory paths (ending in {@code /}) are served from a
 * sibling {@code listing.json} fixture, raw files are served as-is.
 */
public class RepositoryContentListingHandler implements HttpExchangeHandler {

    private static final String PREFIX = "/service/local/repositories/";

    @Override
    public boolean tryHandle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!"GET".equals(ex.getRequestMethod()) || !path.startsWith(PREFIX)) {
            return false;
        }
        int contentIdx = path.indexOf("/content");
        if (contentIdx < 0) {
            return false;
        }
        String repoAndAfter = path.substring(PREFIX.length(), contentIdx); // e.g. orgapachesling-3
        String relative = path.substring(contentIdx + "/content".length()); // e.g. /org/apache/sling/
        String resource;
        if (relative.endsWith("/")) {
            resource = "/nexus-content/" + repoAndAfter + relative + "listing.json";
        } else {
            resource = "/nexus-content/" + repoAndAfter + relative;
        }
        serveFileFromClasspath(ex, resource);
        return true;
    }
}
