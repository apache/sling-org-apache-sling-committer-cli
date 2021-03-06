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
package org.apache.sling.cli.impl.mail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.MimeMessage;
import javax.net.ssl.SSLContext;

import org.apache.sling.cli.impl.Credentials;
import org.apache.sling.cli.impl.CredentialsService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(
        service = Mailer.class
)
public class Mailer {

    private static final Logger LOGGER = LoggerFactory.getLogger(Mailer.class);

    private static final Properties SMTP_PROPERTIES = new Properties();
    static {
        try {
            SMTP_PROPERTIES.put("mail.smtp.host", "mail-relay.apache.org");
            SMTP_PROPERTIES.put("mail.smtp.port", "465");
            SMTP_PROPERTIES.put("mail.smtp.auth", "true");
            SMTP_PROPERTIES.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            SMTP_PROPERTIES.put("mail.smtp.socketFactory.fallback", "false");
            SMTP_PROPERTIES.put("mail.smtp.ssl.protocols",
                    String.join(" ", SSLContext.getDefault().getSupportedSSLParameters().getProtocols())
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Reference
    private CredentialsService credentialsService;

    public void send(String source) {
        Properties properties = new Properties(SMTP_PROPERTIES);
        Session session = Session.getInstance(properties);
        try {
            MimeMessage message = new MimeMessage(session, new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
            Credentials credentials = credentialsService.getAsfCredentials();
            Transport.send(message, credentials.getUsername(), credentials.getPassword());
        } catch (MessagingException e) {
            LOGGER.error(String.format("Unable to send the following email:%n%s", source), e);
        }

    }

}
