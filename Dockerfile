FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/mkmconnector-fatjar.jar /app
