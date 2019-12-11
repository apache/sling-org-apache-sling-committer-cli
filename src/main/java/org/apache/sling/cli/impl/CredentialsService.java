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
package org.apache.sling.cli.impl;

import java.util.Optional;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

@Component(service = CredentialsService.class)
public class CredentialsService {

    private static final ValueSource ASF_USER = new ValueSource("asf.username", "ASF_USERNAME", "ASF user information");
    private static final ValueSource ASF_PWD = new ValueSource("asf.password", "ASF_PASSWORD", "ASF password");

    private Credentials asfCredentials;

    @Activate
    protected void activate() {
        asfCredentials = new Credentials(ASF_USER.getValue(), ASF_PWD.getValue());
    }
    
    public Credentials getAsfCredentials() {
        return asfCredentials;
    }

    static class ValueSource {

        private final String sysProp;
        private final String envVar;
        private final String friendlyName;
        
        public ValueSource(String sysProp, String envVar, String friendlyName) {

            this.sysProp = sysProp;
            this.envVar = envVar;
            this.friendlyName = friendlyName;
        }
        
        public String getValue() {
            
            return Optional.ofNullable(System.getProperty(sysProp))
                    .or( () -> Optional.ofNullable(System.getenv(envVar)) )
                    .orElseThrow(() -> new IllegalStateException(String.format("Cannot detect %s after looking for %s system property and %s environment variable.", 
                            friendlyName, sysProp, envVar)));
                    
        }   
    }
}
