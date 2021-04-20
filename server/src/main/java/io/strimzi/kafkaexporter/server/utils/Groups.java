/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.utils;

import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListConsumerGroupsResult;
import org.apache.kafka.common.KafkaFuture;

import java.util.Collection;
import java.util.function.Function;

/**
 * @author Ales Justin
 */
public enum Groups implements Function<ListConsumerGroupsResult, KafkaFuture<Collection<ConsumerGroupListing>>> {
    ALL(ListConsumerGroupsResult::all),
    VALID(ListConsumerGroupsResult::valid);

    private final Function<ListConsumerGroupsResult, KafkaFuture<Collection<ConsumerGroupListing>>> fn;

    Groups(Function<ListConsumerGroupsResult, KafkaFuture<Collection<ConsumerGroupListing>>> fn) {
        this.fn = fn;
    }

    public KafkaFuture<Collection<ConsumerGroupListing>> apply(ListConsumerGroupsResult result) {
        return fn.apply(result);
    }
}
