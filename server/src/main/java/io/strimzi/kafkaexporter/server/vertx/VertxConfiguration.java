/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.vertx;

import io.strimzi.kafkaexporter.server.AsyncCollector;
import io.strimzi.kafkaexporter.server.registry.MeterRegistryExporter;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

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

    HttpServer server;

    public void init(@Observes Router router) {
        log.info("Adding custom metrics paths ...");
        router.get("/").handler(new IndexHandler(metricsPath));
        router.route(metricsPath).handler(new AsyncMetricsHandler(exporter, collector));
        router.route(healthPath).handler(routingContext -> {
            routingContext.response().putHeader(CONTENT_TYPE, "text").end("OK");
        });
    }
}