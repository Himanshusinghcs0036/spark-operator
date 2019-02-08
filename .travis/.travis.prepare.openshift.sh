#!/bin/bash

set -xe

RUN=${RUN:-"1"}

download_openshift() {
  echo "Downloading oc binary for VERSION=${VERSION}"
  sudo docker cp $(docker create docker.io/openshift/origin:$VERSION):/bin/oc /usr/local/bin/oc
  oc version
}

setup_insecure_registry() {
  sudo cat /etc/default/docker
  sudo service docker stop
  sudo sed -i -e 's/sock/sock --insecure-registry 172.30.0.0\/16/' /etc/default/docker
  sudo cat /etc/default/docker
  sudo service docker start
  sudo service docker status
  sudo mount --make-rshared /
}

setup_manifest() {
  sed -i'' 's;quay.io/radanalyticsio/spark-operator:latest-released;radanalyticsio/spark-operator:latest;g' manifest/operator.yaml
  sed -i'' 's;quay.io/radanalyticsio/spark-operator:latest-released;radanalyticsio/spark-operator:latest;g' manifest/operator-crd.yaml
  sed -i'' 's;imagePullPolicy: .*;imagePullPolicy: Never;g' manifest/operator.yaml
  sed -i'' 's;imagePullPolicy: .*;imagePullPolicy: Never;g' manifest/operator-crd.yaml
  [ "$CRD" = "1" ] && FOO="-crd" || FOO=""
  echo -e "'\nmanifest${FOO}:\n-----------\n"
  cat manifest/operator${FOO}.yaml
}

main() {
  echo -e "travis_fold:start:oc\033[33;1mPrepare OpenShift\033[0m"
  download_openshift
  setup_insecure_registry
  setup_manifest
  echo -e "\ntravis_fold:end:oc\r"
}

[ "$RUN" = "1" ] && main || echo "$0 sourced"
