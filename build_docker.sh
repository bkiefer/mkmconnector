#!/bin/sh
if test "-n" = "$1"; then
    shift
    mvn clean install -DskipTests
else
    mvn clean install
fi

pom_version() {
    # There are deprecation warnings under the hood!
    mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null
}

docker build -f Dockerfile -t mkmconnector:`pom_version` .
