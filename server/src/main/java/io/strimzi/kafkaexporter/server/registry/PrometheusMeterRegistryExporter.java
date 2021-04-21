/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.registry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * @author Ales Justin
 */
@Singleton
public class PrometheusMeterRegistryExporter implements MeterRegistryExporter {
    private static final Logger log = LoggerFactory.getLogger(PrometheusMeterRegistryExporter.class);

    @Inject
    Instance<PrometheusMeterRegistry> registries;

    private PrometheusMeterRegistry registry;

    @PostConstruct
    void init () {
        if (registries.isUnsatisfied()) {
            registry = null;
        } else if (registries.isAmbiguous()) {
            registry = registries.iterator().next();
            log.warn("Multiple Prometheus registries present. Using {} with the built-in scrape endpoint", registry);
        } else {
            registry = registries.get();
        }
    }

    @Override
    public boolean canExport() {
        return registry != null;
    }

    @Override
    public String scrape(List<Meter> meters, Writer writer) throws IOException {
        registry.scrape(writer);
        return TextFormat.CONTENT_TYPE_004;
    }
}
