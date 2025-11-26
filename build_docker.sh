#!/bin/sh
mvn clean install
docker build -f Dockerfile -t mkmconnector .
