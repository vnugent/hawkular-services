#!/usr/bin/env bash
#
# Copyright 2016-2017 Red Hat, Inc. and/or its affiliates
# and other contributors as indicated by the @author tags.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# exit codes:
# 0 ok
# 1 at least /status is broken
# 2 at least /metrics/status  is broken
# 3 at least /alerts/status is broken
# 4 /inventory/status is broken

i=0
for path in /status /metrics/status /alerts/status /inventory/status ; do
  i=$(( $i + 1 ))
  code=`curl -s -I -o /dev/null -w "%{http_code}" http://$HOSTNAME:8080/hawkular$path` || exit $i
  [[ "$code" -lt "200" || "$code" -gt "299" ]] && exit $i
done

# everything is ok
exit 0
