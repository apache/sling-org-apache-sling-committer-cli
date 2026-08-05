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
package org.apache.sling.cli.impl.junit;

import java.util.List;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.rules.ExternalResource;
import org.slf4j.LoggerFactory;

/**
 * JUnit rule that captures log output for a given class using Logback's {@link ListAppender}.
 * Usage:
 * <pre>
 * &#64;Rule
 * public LogCapture logCapture = new LogCapture(MyClass.class);
 *
 * &#64;Test
 * public void test() {
 *     // ... exercise code ...
 *     assertTrue(logCapture.containsMessage("expected output"));
 * }
 * </pre>
 */
public class LogCapture extends ExternalResource {

    private final Class<?>[] loggerClasses;
    private ListAppender<ILoggingEvent> appender;

    public LogCapture(Class<?>... loggerClasses) {
        this.loggerClasses = loggerClasses;
    }

    @Override
    protected void before() {
        appender = new ListAppender<>();
        appender.start();
        for (Class<?> clazz : loggerClasses) {
            Logger logger = (Logger) LoggerFactory.getLogger(clazz);
            logger.addAppender(appender);
        }
    }

    @Override
    protected void after() {
        for (Class<?> clazz : loggerClasses) {
            Logger logger = (Logger) LoggerFactory.getLogger(clazz);
            logger.detachAppender(appender);
        }
        appender.stop();
    }

    public List<String> getMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    public boolean containsMessage(String substring) {
        return appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains(substring));
    }

    public String messageAt(int index) {
        return appender.list.get(index).getFormattedMessage();
    }

    public int size() {
        return appender.list.size();
    }
}
