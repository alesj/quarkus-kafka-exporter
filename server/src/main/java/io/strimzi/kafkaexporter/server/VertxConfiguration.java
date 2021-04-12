package io.strimzi.kafkaexporter.server;

import io.prometheus.client.vertx.MetricsHandler;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
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

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "metrics.path", defaultValue = "/metrics")
    String metricsPath;

    @ConfigProperty(name = "prometheus.http.port", defaultValue = "9308")
    int httpPort;

    HttpServer server;

    public void init(@Observes StartupEvent event) {
        Router router = Router.router(vertx);

        router.route("/").handler(new IndexHandler(metricsPath));
        router.route(metricsPath).handler(new MetricsHandler());
        router.route("/healthz").handler(routingContext -> {
            routingContext.response().putHeader("content-type", "text").end("OK");
        });

        server = vertx.createHttpServer().requestHandler(router).listen(httpPort);
        log.info("Started Vertx http server on port {}", httpPort);
    }

    public void destroy(@Observes ShutdownEvent event) {
        if (server != null) {
            server.close();
        }
    }
}