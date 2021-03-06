/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.vertx;

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
            .putHeader(VertxConfiguration.CONTENT_TYPE, "text/html")
            .end("<html>\n" +
                "<head><title>Kafka Exporter</title></head>\n" +
                "<body>\n" +
                "<h1>Kafka Exporter</h1>\n" +
                "<p><a href='" + metricsPath + "'>Metrics</a></p>\n" +
                "</body>\n" +
                "</html>");
    }
}
