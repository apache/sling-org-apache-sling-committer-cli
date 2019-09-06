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

echo "################################################################################"
echo "                           LOAD GPG KEYS                                        "
echo "################################################################################"

curl https://people.apache.org/keys/group/sling.asc --output /tmp/sling.asc
if [ "$?" != "0" ]; then
  echo "Failed to download Sling GPG Keys"
  exit 1;
fi
gpg --import /tmp/sling.asc
if [ "$?" != "0" ]; then
  echo "Failed to load Sling GPG Keys"
  exit 1;
fi

echo "################################################################################"
echo "                          CHECK SIGNATURES AND DIGESTS                          "
echo "################################################################################"

RESULT=0
for i in `find "$RELEASE_FOLDER" -type f | grep -v '\.\(asc\|sha1\|md5\)$'`
do
  f=`echo $i | sed 's/\.asc$//'`
  echo "$f"
  if [ ! -f "$f.asc" ]; then
    CHKSUM="----";
  else
    gpg --verify $f.asc 2>/dev/null
    if [ "$?" = "0" ]; then
      CHKSUM="GOOD";
    else
      CHKSUM="BAD!!!!!!!!";
      RESULT=2
    fi
  fi
  echo "gpg:  ${CHKSUM}"

 for tp in md5 sha1
 do
   if [ ! -f "$f.$tp" ]
   then
     CHKSUM="----"
   else
     A="`cat $f.$tp 2>/dev/null`"
     B="`openssl $tp < $f 2>/dev/null | sed 's/.*= *//' `"
     if [ "$A" = "$B" ]; then
       CHKSUM="GOOD (`cat $f.$tp`)";
     else
       CHKSUM="BAD!! : $A not equal to $B";
       RESULT=3
     fi
   fi
   echo "$tp : ${CHKSUM}"
 done

done

if [ -z "${CHKSUM}" ]; then
  echo "WARNING: no files found!";
  RESULT=4
fi

echo "################################################################################"

exit $RESULT
