/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.utils;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author Ales Justin
 */
public class MutableSupplier implements Supplier<Number>, Consumer<Number> {
    private volatile Number value;

    public MutableSupplier(Number value) {
        this.value = value;
    }

    @Override
    public void accept(Number number) {
        value = number;
    }

    @Override
    public Number get() {
        return value;
    }
}
