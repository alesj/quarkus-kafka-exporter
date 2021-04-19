package io.strimzi.kafkaexporter.server;

import io.prometheus.client.Collector;
import io.prometheus.client.exporter.BasicAuthHttpConnectionFactory;
import io.prometheus.client.exporter.HttpConnectionFactory;
import io.prometheus.client.exporter.PushGateway;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.io.IOException;
import java.net.URL;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author Ales Justin
 */
@ApplicationScoped
public class PushGatewayConfiguration {
    private static final Logger log = LoggerFactory.getLogger(PushGatewayConfiguration.class);

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

    @Inject
    Collector collector;

    private PushGateway gateway;
    private ScheduledExecutorService service;

    public void init(@Observes StartupEvent event) throws Exception {
        if (url.isPresent()) {
            URL serverBaseURL = url.get();
            log.info("Prometheus pushgateway enabled: {}", serverBaseURL);
            gateway = new PushGateway(serverBaseURL);
            if (factoryClass.isPresent()) {
                Class<HttpConnectionFactory> clazz = factoryClass.get();
                HttpConnectionFactory factory = clazz.getConstructor().newInstance();
                gateway.setConnectionFactory(factory);
            } else if (username.isPresent() && password.isPresent()) {
                gateway.setConnectionFactory(new BasicAuthHttpConnectionFactory(username.get(), password.get()));
            }
            service = new ScheduledThreadPoolExecutor(poolSize);
            service.scheduleAtFixedRate(this::pushCollector, initialDelay, period, unit);
        }
    }

    public void destroy(@Observes ShutdownEvent event) {
        if (service != null) {
            service.shutdown();
        }
    }

    private void pushCollector() {
        try {
            gateway.push(collector, job);
        } catch (IOException e) {
            log.warn("Cannot push metrics", e);
        }
    }
}