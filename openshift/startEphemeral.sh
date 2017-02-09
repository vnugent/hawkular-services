#/bin/bash

oc cluster up && \
oc new-project test && \
oc new-app template-ephemeral.yaml --param=HAWKULAR_SERVICES_IMAGE=jkremser/hawkular-services:0.32.0.Final,CASSANDRA_IMAGE=openshift/origin-metrics-cassandra:v1.4.1

echo -e "\n\nLet's wait for the route to become accessible, this may take couple of minutes.\n"
sleep 20
printf "["
while oc get pod -l name=hawkular-services -o json | grep "\"ready\": false" > /dev/null; do
  printf "▓"
  sleep 5
done
printf "] it's there!"

URL=`oc get route public | grep hawkular-services | awk '{print $2}'` && \
echo -e "\n\nYour Hawkular Services instance is prepared on $(tput setaf 2)http://$URL $(tput sgr0) \n"
