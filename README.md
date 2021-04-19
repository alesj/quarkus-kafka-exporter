# Quarkus Kafka Exporter

[Quarkus](https://quarkus.io/) based copy/mock of [Kafka Exporter](https://github.com/danielqsj/kafka_exporter)

It supports all the same metrics, apart from Zookeeper consumer groups lag.

Any Kafka config should start with `kafka.`, and then use exact key from Kafka config.

```
e.g. kafka.bootstrap.servers=mykafka:9092
```

Other config includes (same as kafka_exporter)
```
    @ConfigProperty(name = "metrics.path", defaultValue = "/metrics")
    String metricsPath;

    @ConfigProperty(name = "prometheus.http.port", defaultValue = "9308")
    int httpPort;

    @ConfigProperty(name = "refresh.metadata", defaultValue = "PT1M")
    Duration metadataRefreshInterval;

    @ConfigProperty(name = "namespace", defaultValue = "kafka")
    String namespace;

    @ConfigProperty(name = "topic.filter", defaultValue = ".*")
    Pattern topicFilter;

    @ConfigProperty(name = "group.filter", defaultValue = ".*")
    Pattern groupFilter;

    @ConfigProperty(name = "kafka.labels")
    Optional<String> kafkaLabels;
```

Additionally there is a PushGateway support, which is enabled if you configure `pushgateway.url` property.

```
    @ConfigProperty(name = "pushgateway.url")
    Optional<URL> url;

    @ConfigProperty(name = "pushgateway.factory")
    Optional<Class<HttpConnectionFactory>> factoryClass;

    @ConfigProperty(name = "pushgateway.username")
    Optional<String> username;

    @ConfigProperty(name = "pushgateway.password")
    Optional<String> password;

    @ConfigProperty(name = "pushgateway.job", defaultValue = "kafka")
    String job;

    @ConfigProperty(name = "pushgateway.pool-size", defaultValue = "2")
    int poolSize;
    @ConfigProperty(name = "pushgateway.initial-delay", defaultValue = "60")
    long initialDelay;
    @ConfigProperty(name = "pushgateway.period", defaultValue = "60")
    long period;
    @ConfigProperty(name = "pushgateway.unit", defaultValue = "SECONDS")
    TimeUnit unit;
```

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