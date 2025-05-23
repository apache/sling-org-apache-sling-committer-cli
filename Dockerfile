# ----------------------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one or more contributor license
# agreements. See the NOTICE file distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file to you under the Apache License,
# Version 2.0 (the "License"); you may not use this file except in compliance with the
# License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software distributed under the
# License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied. See the License for the specific language governing permissions
# and limitations under the License.
# ----------------------------------------------------------------------------------------
FROM azul/zulu-openjdk-alpine:17 as builder
RUN apk add --no-cache binutils
RUN $JAVA_HOME/bin/jlink --add-modules java.logging,java.naming,java.xml,java.security.jgss,java.sql,jdk.crypto.ec,java.desktop  --output /opt/jre --strip-debug --compress=2 --no-header-files --no-man-pages

FROM alpine

LABEL org.opencontainers.image.authors="dev@sling.apache.org"
LABEL org.opencontainers.image.url="https://github.com/apache/sling-org-apache-sling-committer-cli/"
LABEL org.opencontainers.image.vendor="Apache Software Foundation"
LABEL org.opencontainers.image.licenses="Apache-2.0"

COPY --from=builder /opt/jre /opt/jre

# Generate class data sharing
RUN /opt/jre/bin/java -Xshare:dump

# escaping required to properly handle arguments with spaces
ENTRYPOINT ["/usr/share/sling-cli/bin/run.sh"]
CMD ["help"]

# Add feature launcher
COPY target/lib/feature-launcher /usr/share/sling-cli/launcher
# Add launcher script
COPY target/classes/scripts/run.sh /usr/share/sling-cli/bin/run.sh
# workaround for MRESOURCES-236
RUN chmod a+x /usr/share/sling-cli/bin/*
# Add config files
COPY target/classes/conf /usr/share/sling-cli/conf
# Add all bundles
COPY target/artifacts /usr/share/sling-cli/artifacts
# Add the service itself
ARG FEATURE_FILE
COPY ${FEATURE_FILE} /usr/share/sling-cli/sling-cli.feature

RUN mkdir /usr/share/sling-cli/secrets