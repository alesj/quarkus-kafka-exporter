# Quarkus Kafka Exporter

[Quarkus](https://quarkus.io/) based copy/mock of [Kafka Exporter](https://github.com/danielqsj/kafka_exporter)

It supports all the same metrics, apart from Zookeeper consumer groups lag.

TODO ... more docs

## Build

mvn clean install -DskipTests

## Runner

java -jar server/target/quarkus-app/quarkus-run.jar

## Docker

mvn clean install -DskipTests -Pdocker

docker run -i --rm -p 8080:8080 strimzi/kafka-exporter-server:latest

docker run --rm -it --entrypoint=/bin/sh  strimzi/kafka-exporter-server:latest

## Native

mvn clean install -DskipTests -Dquarkus.container-image.build=true -Pnative

cd server

docker build -f src/main/docker/Dockerfile.native -t docker.io/strimzi/kafka-exporter-server:latest .