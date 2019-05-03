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
package org.apache.sling.cli.impl;

import org.osgi.service.component.ComponentContext;

public class ComponentContextHelper {

    public static ComponentContextHelper wrap(ComponentContext wrapped) {
   
        return new ComponentContextHelper(wrapped);
    }

    private final ComponentContext wrapped;

    public ComponentContextHelper(ComponentContext wrapped) {
        this.wrapped = wrapped;
    }
    
    public String getProperty(String name, String fallback) {
        Object prop = wrapped.getProperties().get(name);
        if ( prop != null) {
            return prop.toString();
        }
        
        return fallback;
    }
    
    public int getProperty(String name, int fallback) {
        
        return Integer.parseInt(getProperty(name, String.valueOf(fallback)));
    }
}
