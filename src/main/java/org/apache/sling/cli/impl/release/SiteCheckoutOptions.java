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
package org.apache.sling.cli.impl.release;

import picocli.CommandLine;

/** Shared by the commands that edit the Sling website, so they take the checkout location the same way. */
class SiteCheckoutOptions {

    @CommandLine.Option(
            names = {"--site-checkout"},
            defaultValue = "${sys:user.home}/.sling-cli/sling-site",
            description = "Directory holding the sling-site checkout. It is cloned when missing and reset to the"
                    + " published branch before each run, so point it at a directory dedicated to this rather than"
                    + " at a checkout you work in. Inside the container this is discarded with the container unless"
                    + " the parent directory is mounted. Default: ${DEFAULT-VALUE}")
    String checkout;
}
