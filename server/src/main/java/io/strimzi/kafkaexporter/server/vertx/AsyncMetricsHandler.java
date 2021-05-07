/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.vertx;

import io.strimzi.kafkaexporter.server.AsyncCollector;
import io.strimzi.kafkaexporter.server.CollectorResult;
import io.strimzi.kafkaexporter.server.registry.MeterRegistryExporter;
import io.vertx.ext.web.RoutingContext;

import java.io.IOException;
import java.net.HttpURLConnection;

/**
 * @author Ales Justin
 */
public class AsyncMetricsHandler extends AbstractMetricsHandler {
    public AsyncMetricsHandler(MeterRegistryExporter exporter, AsyncCollector collector) {
        super(exporter, collector);
    }

    @Override
    protected void handleInternal(RoutingContext ctx) {
        CollectorResult result = collector.collect();
        result.getFuture().whenComplete((meters, t) -> {
            if (t != null) {
                ctx.fail(t);
            } else {
                final BufferWriter writer = new BufferWriter();
                try {
                    String contentType = exporter.export(meters, writer);
                    ctx.response()
                        .setStatusCode(HttpURLConnection.HTTP_OK)
                        .putHeader(VertxConfiguration.CONTENT_TYPE, contentType)
                        .end(writer.getBuffer());
                } catch (IOException e) {
                    ctx.fail(e);
                }
            }
        });
    }
}
