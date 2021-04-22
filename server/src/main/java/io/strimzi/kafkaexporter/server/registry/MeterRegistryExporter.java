/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.registry;

import io.micrometer.core.instrument.Meter;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * @author Ales Justin
 */
public interface MeterRegistryExporter {
    /**
     * Can we export metrics?
     *
     * @return true if yes, false for no
     */
    boolean canExport();

    /**
     * Export meters data into writer.
     *
     * @param meters the collected meters
     * @param writer the write to write to
     * @return content-type
     * @throws IOException for any I/O error
     */
    String export(List<Meter> meters, Writer writer) throws IOException;
}
