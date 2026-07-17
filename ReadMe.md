[![mvn build](https://github.com/bkiefer/mkm/actions/workflows/maven.yml/badge.svg)](https://github.com/bkiefer/mkm/actions/workflows/maven.yml)

# DRZ Mission Knowledge Manager Connector

## Installation instructions for use with Docker and docker compose

## Prerequisites

These instructions have been tested on **Ubuntu 24.04** and higher, but they should also work on other Linux distributions.

### OS Requirements:

* **Java 21 JDK** or higher (`openjdk-21-jdk`)
* **Maven**
* **git**

Additionally, the following tools are required:

* **Docker** and **Docker Compose**

Ensure that the user installing the software is part of the **docker** group. You can add a user to this group using the following command:

```bash
sudo usermod -aG docker <username>
```

---

## Setup Instructions

After cloning the repository, run the following command to create the docker image

```bash
./build_docker.sh
```

## Configuration

The `config.yml` resp. `config-docker.yml` sets the URL for the endpoint to connect to, while `credentials.yaml` needs to specify the required credentials in the following form:

```yaml
iais:
  user: "test-user"
  password: "test-password"
```

## Test the MKM connector

The junit tests executed during `mvn install` test all functionality available in the connector. If they fail, the configuration in `src/test/resources` and the `credentials.yml`, which has to be given in the root directory, may need adaptation. In case there currently is no accessible test endpoint, run the installation without tests:

    `mvn install -DskipTests`
