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

pom_files=$(find $RELEASE_FOLDER -name '*.pom')

failed=0
unknown=0

echo ""
for pom_file in ${pom_files}; do
  artifactId=$(xmllint --xpath "/*[local-name()='project']/*[local-name()='artifactId']/text()" ${pom_file})
  version=$(xmllint --xpath "/*[local-name()='project']/*[local-name()='version']/text()" ${pom_file})
  repo_name="${artifactId//\./-}"
  if [[ $repo_name != sling-* ]]; then
    repo_name="sling-${repo_name}"
  fi
  echo -n "STATUS: ${artifactId} ${version}: "
  resp=$(curl --silent -H 'Accept: application/vnd.github.v3+json' \
      "https://api.github.com/repos/apache/${repo_name}/commits/${artifactId}-${version}/status")
  status=$(echo $resp | jq --raw-output '.state')
  echo $status
  case $status in
    "pending")
      unknown=1
      ;;
    "failure")
      failed=1
      ;;
  esac
  if [[ $status != "success" ]]; then
    echo "See https://github.com/apache/${repo_name}/commits/${artifactId}-${version} for details"
    echo $resp | jq -r '.statuses[] | "Additional Information: \"" + .description + "\" See: " + .target_url'
  fi
  echo ""
done

if [ $failed -eq 1 ]; then
  exit 1
fi

if [ $unknown -eq 1 ]; then
  exit 129
fi

exit 0
