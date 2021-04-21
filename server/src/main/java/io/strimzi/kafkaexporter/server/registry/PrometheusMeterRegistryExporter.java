/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.registry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.common.TextFormat;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * @author Ales Justin
 */
@ApplicationScoped
public class PrometheusMeterRegistryExporter implements MeterRegistryExporter {

    @Inject
    MeterRegistry registry;

    private volatile boolean check;
    private PrometheusMeterRegistry pmr;

    private PrometheusMeterRegistry getRegistry() {
        if (!check) {
            if (registry instanceof CompositeMeterRegistry) {
                CompositeMeterRegistry cmr = (CompositeMeterRegistry) registry;
                pmr = (PrometheusMeterRegistry) cmr.getRegistries()
                    .stream()
                    .filter(m -> m instanceof PrometheusMeterRegistry)
                    .findFirst()
                    .orElse(null);
            } else if (registry instanceof PrometheusMeterRegistry) {
                pmr = (PrometheusMeterRegistry) registry;
            }
            check = true;
        }
        return pmr;
    }

    @Override
    public boolean canExport() {
        return getRegistry() != null;
    }

    @Override
    public String scrape(List<Meter> meters, Writer writer) throws IOException {
        getRegistry().scrape(writer);
        return TextFormat.CONTENT_TYPE_004;
    }
}
