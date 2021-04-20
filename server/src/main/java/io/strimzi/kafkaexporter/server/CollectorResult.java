/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server;

import io.prometheus.client.Collector;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author Ales Justin
 */
public interface CollectorResult {
    CompletableFuture<List<Collector.MetricFamilySamples>> getFuture();
    List<Collector.MetricFamilySamples> getCompleted();
}
