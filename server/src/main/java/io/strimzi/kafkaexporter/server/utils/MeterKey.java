/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.utils;

import io.micrometer.core.instrument.Tag;

import java.util.List;
import java.util.Objects;

/**
 * @author Ales Justin
 */
public class MeterKey {
    private final String fqn;
    private final List<Tag> tags;

    public MeterKey(String fqn, List<Tag> tags) {
        this.fqn = fqn;
        this.tags = tags;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MeterKey meterKey = (MeterKey) o;
        return Objects.equals(fqn, meterKey.fqn) && equals(tags, meterKey.tags);
    }

    private static boolean equals(List<Tag> left, List<Tag> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            Tag l = left.get(i);
            Tag r = right.get(i);
            if (!l.equals(r)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fqn, tags);
    }
}
