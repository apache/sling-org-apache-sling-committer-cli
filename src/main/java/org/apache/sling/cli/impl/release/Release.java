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
package org.apache.sling.cli.impl.release;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides structured access to the components of a release name.
 */
public final class Release {

    /*
        Group 1: Apache Sling and any trailing whitespace (optional)
        Group 2: Release component
        Group 3: Release version
        Group 4: RC status (optional)
     */
    private static final Pattern RELEASE_PATTERN = Pattern.compile("^\\h*(Apache Sling\\h*)?([()a-zA-Z0-9\\-.\\h]+)\\h([0-9\\-.]+)" +
            "\\h?(RC[0-9.]*)?\\h*$");
    
    public static List<Release> fromString(String repositoryDescription) {

        List<Release> releases = new ArrayList<>();
        for (String item  : repositoryDescription.split(",") ) {
            
            Matcher matcher = RELEASE_PATTERN.matcher(item);
            if (matcher.matches()) {
                Release rel = new Release();
                rel.component = matcher.group(2).trim();
                rel.version = matcher.group(3);
                rel.name = rel.component + " " + rel.version;
                StringBuilder fullName = new StringBuilder();
                if (matcher.group(1) != null) {
                    fullName.append(matcher.group(1).trim()).append(" ");
                }
                fullName.append(rel.name);
                rel.fullName = fullName.toString();
                
                releases.add(rel);
            }
        }
        
        if ( releases.isEmpty() )
            throw new IllegalArgumentException("No releases found in '" + repositoryDescription + "'");
        
        return releases;
    }
    
    private String fullName;
    private String name;
    private String component;
    private String version;

    private Release() {
        
    }
    
    /**
     * Returns the full name, e.g. <em>Apache Sling Foo 1.0.2</em>
     * 
     * @return the full name
     */
    public String getFullName() {
        return fullName;
    }
    
    /**
     * Returns the name, e.g. <em>Foo 1.0.2</em>
     * 
     * @return the name
     */
    public String getName() {
        return name;
    }
    
    /**
     * Returns the version, e.g. <em>1.0.2</em>
     * 
     * @return the version 
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns the component, e.g. <tt>Foo</tt>
     * 
     * @return the component
     */
    public String getComponent() {
        return component;
    }

    /**
     * Creates a new Release object that corresponds to the next release name
     * 
     * <p>The next object is identical to <tt>this</tt> object, except the fact that the
     * micro component of the version is increased by two.</P>
     * 
     * <p>For instance, the next version of <tt>Apache Sling Foo 1.0.2</tt> is <tt>Apache Sling Foo 1.0.4</tt>.</p>
     * 
     * @return the next release
     */
    public Release next() {
        
        // assumption is that the release object is well-formed
        int lastSeparator = fullName.lastIndexOf('.'); // Apache Sling Foo 1.0.2 -> 1.0.4
        int increment = 2;
        if ( lastSeparator == -1 ) {
            lastSeparator = fullName.lastIndexOf(' '); // Apache Sling Bar 2 -> 3
            increment = 1;
        }
        
        int componentToIncrement = Integer.parseInt(fullName.substring(lastSeparator + 1));
        
        String unchangedPart = fullName.substring(0, lastSeparator + 1);
        
        return Release.fromString(unchangedPart + ( componentToIncrement + increment )).get(0);
    }
    
    @Override
    public int hashCode() {
        return fullName.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Release)) {
            return false;
        }
        Release other = (Release) obj;
        return Objects.equals(name, other.name);
    }

    @Override
    public String toString() {
        return fullName;
    }
}
