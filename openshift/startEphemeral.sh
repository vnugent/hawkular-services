#/bin/bash
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

# todo: use other image once it is published
HAWKULAR_SERVICES_IMAGE="jkremser/hawkular-services:0.32.0-with-readiness-script"
CASSANDRA_IMAGE="openshift/origin-metrics-cassandra:v1.4.1"
PROJECT_NAME="ephemeral"
ROUTE_NAME="hawkular-services"
OC_CLUSTER_VERSION="v1.4.1"

prepare_cluster(){
  oc cluster up --version=$OC_CLUSTER_VERSION && \
  oc new-project $PROJECT_NAME
}

instantiate_template(){
  local _OC_VERSION=`oc version | grep oc | cut -f2 -d' '`
  local _OC_MAJOR=`echo ${_OC_VERSION:1:1}`
  local _OC_MINOR=`echo $_OC_VERSION | cut -f2 -d'.'`

  if [[ $_OC_MAJOR == 1 ]] && [[ $_OC_MINOR -lt 5 ]]; then
    # using the old syntax
    oc process -f template-ephemeral.yaml \
     -v HAWKULAR_SERVICES_IMAGE=$HAWKULAR_SERVICES_IMAGE CASSANDRA_IMAGE=$CASSANDRA_IMAGE ROUTE_NAME=$ROUTE_NAME \
      | oc create -f -
  else
    # using the new syntax
    oc process -f template-ephemeral.yaml --param HAWKULAR_SERVICES_IMAGE=$HAWKULAR_SERVICES_IMAGE \
                                          --param CASSANDRA_IMAGE=$CASSANDRA_IMAGE \
                                          --param ROUTE_NAME=$ROUTE_NAME | oc create -f -
  fi
}

wait_for_it(){
  printf "\n\n\nLet's wait for the route to become accessible, \nthis may take couple of minutes - "
  sleep 15
  printf "$(tput setaf 6)◖$(tput sgr0)"
  sleep 8
  while oc get pod -l name=$ROUTE_NAME -o json | grep "\"ready\": false" > /dev/null; do
    printf "$(tput setaf 6)▮$(tput sgr0)"
    sleep 4
  done
  printf "$(tput setaf 6)◗$(tput sgr0) it's there!"
}

tell_where_it_is_running(){
  URL=`oc get route $ROUTE_NAME | grep $ROUTE_NAME | awk '{print $2}'` && \
  echo -e "\n\nYour Hawkular Services instance is prepared on $(tput setaf 2)http://$URL $(tput sgr0) \n"
}

main(){
  oc &> /dev/null ||  {
    echo "Install the oc client first."
    exit 1
  }
  prepare_cluster && \
  instantiate_template && \
  wait_for_it && \
  tell_where_it_is_running

  RETURN_CODE=$?
  if [[ $RETURN_CODE != 0 ]]; then
    echo  "If it failed in the 'Checking container networking' step, try to shut down the cluster, run sudo iptables -F and try again."
    exit $RETURN_CODE
  fi
}

main
