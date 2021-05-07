/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.vertx;

import io.strimzi.kafkaexporter.server.registry.MeterRegistryExporter;
import io.vertx.ext.web.RoutingContext;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Collections;

/**
 * @author Ales Justin
 */
public class ScheduledMetricsHandler extends AbstractMetricsHandler {
    public ScheduledMetricsHandler(MeterRegistryExporter exporter) {
        super(exporter, null);
    }

    @Override
    protected void handleInternal(RoutingContext ctx) {
        final BufferWriter writer = new BufferWriter();
        try {
            String contentType = exporter.export(Collections.emptyList(), writer);
            ctx.response()
                .setStatusCode(HttpURLConnection.HTTP_OK)
                .putHeader(VertxConfiguration.CONTENT_TYPE, contentType)
                .end(writer.getBuffer());
        } catch (IOException e) {
            ctx.fail(e);
        }
    }
}
