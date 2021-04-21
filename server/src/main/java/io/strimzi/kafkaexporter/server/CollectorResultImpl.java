/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server;

import io.micrometer.core.instrument.Meter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author Ales Justin
 */
public class CollectorResultImpl implements CollectorResult {
    private final List<CompletableFuture<List<Meter>>> futures;

    public CollectorResultImpl(List<CompletableFuture<List<Meter>>> futures) {
        this.futures = futures;
    }

    private CompletableFuture<Void> allOf() {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private List<Meter> getMeters() {
        List<Meter> results = new ArrayList<>();
        for (CompletableFuture<List<Meter>> future : futures) {
            results.addAll(future.join()); // should be completed now
        }
        return results;
    }

    public void join() {
        allOf().join();
    }

    public CompletableFuture<List<Meter>> getFuture() {
        return allOf().thenApply(v -> getMeters());
    }

    public List<Meter> getCompleted() {
        join();
        return getMeters();
    }
}
