/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.vertx;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.strimzi.kafkaexporter.server.AsyncCollector;
import io.strimzi.kafkaexporter.server.registry.MeterRegistryExporter;
import io.vertx.core.Handler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author Ales Justin
 */
@ApplicationScoped
public class VertxConfiguration {
    private static final Logger log = LoggerFactory.getLogger(VertxConfiguration.class);

    public static final String CONTENT_TYPE = "Content-Type";

    @Inject
    MeterRegistryExporter exporter;

    @Inject
    AsyncCollector collector;

    @ConfigProperty(name = "metrics.path", defaultValue = "/metrics")
    String metricsPath;

    @ConfigProperty(name = "health.path", defaultValue = "/healthz")
    String healthPath;

    @ConfigProperty(name = "initial.delay")
    Optional<Duration> initialDelay;

    @ConfigProperty(name = "metrics.period", defaultValue = "PT30S")
    Duration metricsPeriod;

    private ScheduledExecutorService scheduler;

    private boolean useScheduledMetrics() {
        return metricsPeriod.compareTo(Duration.ZERO) > 0;
    }

    public void start(@Observes StartupEvent event) {
        if (useScheduledMetrics()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
            long delay = initialDelay.orElse(Duration.ZERO).toMillis();
            long period = metricsPeriod.toMillis();
            scheduler.scheduleAtFixedRate(() -> collector.collect().join(), delay, period, TimeUnit.MILLISECONDS);
        }
    }

    public void stop(@Observes ShutdownEvent event) {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    public void init(@Observes Router router) {
        log.info("Adding custom metrics paths ...");
        router.get("/").handler(new IndexHandler(metricsPath));
        Handler<RoutingContext> handler = useScheduledMetrics() ?
            new MetricsHandler(exporter) :
            new AsyncMetricsHandler(exporter, collector);
        router.route(metricsPath).handler(handler);
        router.route(healthPath).handler(routingContext -> {
            routingContext.response().putHeader(CONTENT_TYPE, "text").end("OK");
        });
    }
}