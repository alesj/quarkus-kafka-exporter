/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.utils;

import io.micrometer.core.instrument.Meter;

/**
 * @author Ales Justin
 */
public class MeterTuple {
    private final MutableSupplier supplier;
    private final Meter meter;

    public MeterTuple(MutableSupplier supplier, Meter meter) {
        this.supplier = supplier;
        this.meter = meter;
    }

    public MutableSupplier getSupplier() {
        return supplier;
    }

    public Meter getMeter() {
        return meter;
    }
}
