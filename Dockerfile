FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
COPY target/mkmconnector-fatjar.jar /app

CMD [ "/bin/sh", "-c", "java -Xmx64m -jar mkmconnector-fatjar.jar -c config.yml 2>&1 | tee logs/full.logs" ]