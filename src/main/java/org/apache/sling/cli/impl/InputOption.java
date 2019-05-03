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

public class InputOption {

    private final String text;
    private final String mnemonic;

    public static final InputOption YES = new InputOption("Yes", "y");
    public static final InputOption NO = new InputOption("No", "n");

    public InputOption(String text, String mnemonic) {
        this.text = text;
        this.mnemonic = mnemonic;
    }

    public String getText() {
        return text;
    }

    public String getMnemonic() {
        return mnemonic;
    }

    @Override
    public String toString() {
        return text + " (" + mnemonic + ")";
    }

    @Override
    public int hashCode() {
        return text.hashCode() + mnemonic.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof InputOption) {
            InputOption other = (InputOption) obj;
            return text.equals(other.text) && mnemonic.equals(other.mnemonic);
        }
        return false;
    }
}
