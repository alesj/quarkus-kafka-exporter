package io.strimzi.kafkaexporter.server;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * @author Ales Justin
 */
public class IndexHandler implements Handler<RoutingContext> {
    private final String metricsPath;

    public IndexHandler(String metricsPath) {
        this.metricsPath = metricsPath;
    }

    @Override
    public void handle(RoutingContext routingContext) {
        routingContext
            .response()
            .putHeader("content-type", "text/html")
            .end("<html>\n" +
                "<head><title>Kafka Exporter</title></head>\n" +
                "<body>\n" +
                "<h1>Kafka Exporter</h1>\n" +
                "<p><a href='" + metricsPath + "'>Metrics</a></p>\n" +
                "</body>\n" +
                "</html>");
    }
}
