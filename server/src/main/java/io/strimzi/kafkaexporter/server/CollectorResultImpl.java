/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server;

import io.prometheus.client.Collector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author Ales Justin
 */
public class CollectorResultImpl implements CollectorResult {
    private final List<CompletableFuture<List<Collector.MetricFamilySamples>>> futures;

    public CollectorResultImpl(List<CompletableFuture<List<Collector.MetricFamilySamples>>> futures) {
        this.futures = futures;
    }

    private CompletableFuture<Void> allOf() {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private List<Collector.MetricFamilySamples> getMetricFamilySamples() {
        List<Collector.MetricFamilySamples> results = new ArrayList<>();
        for (CompletableFuture<List<Collector.MetricFamilySamples>> future : futures) {
            results.addAll(future.join()); // should be completed now
        }
        return results;
    }

    public CompletableFuture<List<Collector.MetricFamilySamples>> getFuture() {
        return allOf().thenApply(v -> getMetricFamilySamples());
    }

    public List<Collector.MetricFamilySamples> getCompleted() {
        allOf().join();
        return getMetricFamilySamples();
    }
}
