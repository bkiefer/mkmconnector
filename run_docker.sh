#!/bin/bash
if test -z "$1"; then
    echo "Usage: $0 credentials.yml [config.yml]"
    echo "no credentials.yml specified, exiting!"
    exit 1
fi
. ./utils.sh
cd "`dirname $0`"

conffile="config-docker.yml"
if test -n "$2"; then
    conffile="$2"
fi

docker run --rm -d --name "mkmconnector" \
       --add-host host.docker.internal:host-gateway \
       -v ./config-docker.yml:/app/config.yml \
       -v ./"$1":/app/credentials.yml \
       -v ./logs/:/app/logs mkmconnector:`pom_version` \
       /bin/sh -c "java -Xmx64m -jar mkmconnector-fatjar.jar credentials.yml -c config.yml 2>&1 | tee logs/full.logs"
