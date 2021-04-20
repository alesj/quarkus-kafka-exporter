/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server;

/**
 * @author Ales Justin
 */
public interface AsyncCollector {
    CollectorResult collect();
}
