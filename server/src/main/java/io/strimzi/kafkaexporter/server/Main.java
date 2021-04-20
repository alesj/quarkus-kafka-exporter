/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server;

import io.quarkus.runtime.Quarkus;

/**
 * @author Ales Justin
 */
public class Main {
    public static void main(String[] args) {
        Quarkus.run(args);
    }
}
