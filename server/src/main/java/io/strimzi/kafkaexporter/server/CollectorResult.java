/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server;

import io.micrometer.core.instrument.Meter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author Ales Justin
 */
public interface CollectorResult {
    void join(); // wait for the future results to finish
    CompletableFuture<List<Meter>> getFuture();
    List<Meter> getCompleted();
}
