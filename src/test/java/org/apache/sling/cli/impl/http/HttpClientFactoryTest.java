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
package org.apache.sling.cli.impl.http;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.cli.impl.CredentialsService;
import org.apache.sling.cli.impl.junit.SystemPropertiesRule;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HttpClientFactoryTest {

    private HttpServer server;
    private static final Map<String, String> SYSTEM_PROPS = new HashMap<>();

    static {
        SYSTEM_PROPS.put("asf.username", "asf-user");
        SYSTEM_PROPS.put("asf.password", "asf-password");
    }
    @Rule
    public OsgiContext osgiContext = new OsgiContext();

    @Rule
    public SystemPropertiesRule systemProperties = new SystemPropertiesRule(SYSTEM_PROPS);

    @Before
    public void before() throws Throwable {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        HttpContext rootContext = server.createContext("/");
        rootContext.setHandler(ex -> {
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        server.start();
        osgiContext.registerInjectActivateService(new CredentialsService());
        osgiContext.registerInjectActivateService(new HttpClientFactory());
    }

    @Test
    public void test401() {
        HttpClientFactory factory = osgiContext.getService(HttpClientFactory.class);
        if (factory == null) {
            throw new IllegalStateException("Unable to retrieve an HttpClientFactory.");
        }
        CloseableHttpClient client = factory.newClient();
        HttpGet httpGet = new HttpGet("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
        Throwable t = null;
        try {
            client.execute(httpGet);
        } catch (Exception e) {
            t = e;
        }
        assertNotNull(t);
        assertTrue(t.getMessage().contains("Server returned a 401 status; please check your authentication details"));
    }

    @After
    public void after() {
        server.stop(0);
    }
}
