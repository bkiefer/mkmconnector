#!/bin/sh
pom_version() {
    # There are deprecation warnings under the hood!
    mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null
}
