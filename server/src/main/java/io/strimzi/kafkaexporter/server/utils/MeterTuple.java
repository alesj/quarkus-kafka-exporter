/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.utils;

import io.micrometer.core.instrument.Meter;

import java.util.function.Function;

/**
 * @author Ales Justin
 */
public class MeterTuple implements Function<Number, Meter> {
    private final MutableSupplier supplier;
    private final Meter meter;

    public MeterTuple(MutableSupplier supplier, Meter meter) {
        this.supplier = supplier;
        this.meter = meter;
    }

    @Override
    public Meter apply(Number number) {
        supplier.accept(number);
        return meter;
    }
}
