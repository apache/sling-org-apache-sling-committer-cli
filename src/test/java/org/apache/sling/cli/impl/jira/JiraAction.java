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
import java.io.OutputStreamWriter;
import java.util.function.Consumer;

import org.apache.sling.cli.impl.jira.ErrorResponse;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;

public interface JiraAction {
    
    default void error(HttpExchange httpExchange, Gson gson, Consumer<ErrorResponse> c) throws IOException {
        try ( OutputStreamWriter out = new OutputStreamWriter(httpExchange.getResponseBody()) ) {
            httpExchange.sendResponseHeaders(400, 0);
            ErrorResponse er = new ErrorResponse();
            c.accept(er);
            gson.toJson(er, out);
        }
    }
    
    boolean tryHandle(HttpExchange ex) throws IOException;
}
