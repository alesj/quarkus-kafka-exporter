/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.vertx;

import io.strimzi.kafkaexporter.server.AsyncCollector;
import io.strimzi.kafkaexporter.server.registry.MeterRegistryExporter;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

import java.io.Writer;
import java.net.HttpURLConnection;

/**
 * @author Ales Justin
 */
public abstract class AbstractMetricsHandler implements Handler<RoutingContext> {
    /**
     /**
     * Wrap a Vert.x Buffer as a Writer so it can be used with TextFormat writer
     *
     * @author Ales Justin
     */
    static class BufferWriter extends Writer {

        private final Buffer buffer = Buffer.buffer();

        @Override
        public void write(char[] cbuf, int off, int len) {
            buffer.appendString(new String(cbuf, off, len));
        }

        @Override
        public void flush() {
            // NO-OP
        }

        @Override
        public void close() {
            // NO-OP
        }

        Buffer getBuffer() {
            return buffer;
        }
    }

    protected final MeterRegistryExporter exporter;
    protected final AsyncCollector collector;

    public AbstractMetricsHandler(MeterRegistryExporter exporter, AsyncCollector collector) {
        this.exporter = exporter;
        this.collector = collector;
    }

    @Override
    public void handle(RoutingContext ctx) {
        if (!exporter.canExport()) {
            ctx.response()
                .setStatusCode(HttpURLConnection.HTTP_INTERNAL_ERROR)
                .setStatusMessage("Error exporting meter registry data")
                .end();
        } else {
            handleInternal(ctx);
        }
    }

    protected abstract void handleInternal(RoutingContext ctx);
}
