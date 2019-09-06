#!/bin/sh
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

prints() {
  if [ "$2" == "info" ]; then
    COLOR="96m";
  elif [ "$2" == "success" ]; then
    COLOR="92m";
  elif [ "$2" == "error" ]; then
    COLOR="91m";
  else
    COLOR="0m";
  fi
  STARTCOLOR="\e[$COLOR";
  ENDCOLOR="\e[0m";
  printf "\n\n$STARTCOLOR%b$ENDCOLOR" "$1\n";
}

try() {
  "$@"
  local EXIT_CODE=$?

  if [[ $EXIT_CODE -ne 0 ]]; then
    echo "Exit code: $EXIT_CODE"
    prints "Failed to execute command: $@" "error"
    exit 1
  fi
}

# Set variables
export CHECKS=${2:-000-check-signatures,001-check-ci-status}
export RELEASE_ID=$1
export RELEASE_FOLDER="/tmp/release"
export BASE="/usr/share/sling-cli/bin/actions/check-release"

# Set the Maven repo so that we can pull the other release artifacts in a multi-artifact release
mkdir ~/.m2
cat > ~/.m2/settings.xml <<EOF
<settings>
 <profiles>
   <profile>
     <id>staging</id>
     <repositories>
       <repository>
         <id>staging-repo</id>
         <name>your custom repo</name>
         <url>https://repository.apache.org/content/repositories/orgapachesling-$RELEASE_ID</url>
       </repository>
     </repositories>
   </profile>
 </profiles>
 <activeProfiles>
   <activeProfile>staging</activeProfile>
  </activeProfiles>
</settings>
EOF

# Start of the release process
prints "Starting Validation for Apache Sling Release #$RELEASE_ID" "info"

mkdir ${RELEASE_FOLDER} 2>/dev/null

# Download the release artifacts
prints "Downloading release artifacts" "info"
try wget -e "robots=off" -nv -r -np "--reject=html,index.html.tmp" \
  "--follow-tags=" -P "$RELEASE_FOLDER" -nH "--cut-dirs=3" \
  "https://repository.apache.org/content/repositories/orgapachesling-${RELEASE_ID}/org/apache/sling/"

# Execute the checks
for CHECK in $(echo $CHECKS | tr "," "\n")
do
  prints "Executing $CHECK" "info"
  try $BASE/$CHECK.sh
  prints "Check $CHECK executed successfully!" "success"
done

prints "All checks successful!" "success"
