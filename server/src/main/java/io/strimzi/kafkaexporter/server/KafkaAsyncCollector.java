/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.strimzi.kafkaexporter.server.utils.AdminHandle;
import io.strimzi.kafkaexporter.server.utils.AdminProvider;
import io.strimzi.kafkaexporter.server.utils.Groups;
import io.strimzi.kafkaexporter.server.utils.MeterKey;
import io.strimzi.kafkaexporter.server.utils.MeterTuple;
import io.strimzi.kafkaexporter.server.utils.MutableSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.strimzi.kafkaexporter.server.utils.KafkaUtil.toCF;

/**
 * @author Ales Justin
 */
@ApplicationScoped
public class KafkaAsyncCollector implements AsyncCollector {

    @ConfigProperty(name = "namespace", defaultValue = "kafka")
    String namespace;

    @ConfigProperty(name = "topic.filter", defaultValue = ".*")
    Pattern topicFilter;

    @ConfigProperty(name = "group.filter", defaultValue = ".*")
    Pattern groupFilter;

    @ConfigProperty(name = "group.list", defaultValue = "VALID")
    Groups groups; // list all or just valid groups; see ListConsumerGroupsResult

    @ConfigProperty(name = "kafka.labels")
    Optional<String> kafkaLabels;

    private Map<String, String> labels;

    private final Map<MeterKey, MeterTuple> metrics = new ConcurrentHashMap<>();

    @Inject
    AdminProvider adminProvider;

    @Inject
    MeterRegistry registry;

    @Inject
    Executor executor;

    private synchronized Map<String, String> getLabels() {
        if (labels == null) {
            if (kafkaLabels.isPresent()) {
                labels = new LinkedHashMap<>();
                String[] split = kafkaLabels.get().split(",");
                for (String s : split) {
                    String[] pairs = s.split("=");
                    if (pairs.length != 2) {
                        throw new IllegalArgumentException("Invalid kafka.labels property: " + kafkaLabels);
                    }
                    labels.put(pairs[0], pairs[1]);
                }
            } else {
                labels = Collections.emptyMap();
            }
        }
        return labels;
    }

    private List<Tag> tags(List<String> labels, List<Object> values) {
        if (labels.size() != values.size()) {
            throw new IllegalArgumentException("Labels and values size are not equal!");
        }
        Map<String, String> tags = new LinkedHashMap<>(getLabels());
        for (int i = 0; i < labels.size(); i++) {
            tags.put(labels.get(i), String.valueOf(values.get(i)));
        }
        return tags
            .entrySet()
            .stream()
            .map(e -> Tag.of(e.getKey(), e.getValue()))
            .collect(Collectors.toList());
    }

    private List<String> toLabels(String... ls) {
        return Arrays.asList(ls);
    }

    private List<Object> toValues(Object... vs) {
       return Arrays.asList(vs);
    }

    private String fqn(String system, String name) {
        if (namespace != null && system != null) {
            return namespace + "_" + system + "_" + name;
        }
        if (namespace != null) {
            return namespace + "_" + name;
        }
        if (system != null) {
            return system + "_" + name;
        }
        return name;
    }

    private Collection<String> filterTopics(Collection<String> topics) {
        return topics.stream().filter(s -> topicFilter.matcher(s).matches()).collect(Collectors.toSet());
    }

    private Collection<String> toGroupIds(Collection<ConsumerGroupListing> listings) {
        return listings
            .stream()
            .map(ConsumerGroupListing::groupId)
            .filter(id -> groupFilter.matcher(id).matches())
            .collect(Collectors.toSet());
    }

    private Meter toGauge(String fqn, String help, Number value, List<String> labels, List<Object> values) {
        List<Tag> tags = tags(labels, values);
        MeterKey key = new MeterKey(fqn, tags);
        MeterTuple mt = metrics.computeIfAbsent(key, mk -> {
            MutableSupplier supplier = new MutableSupplier(value);
            Gauge gauge = Gauge.builder(fqn, supplier)
                .description(help)
                .tags(tags)
                .register(registry);
            return new MeterTuple(supplier, gauge);
        });
        mt.getSupplier().accept(value);
        return mt.getMeter();
    }

    private <T> void collectNumber(List<CompletableFuture<List<Meter>>> tasks, String fqn, String help, CompletableFuture<T> cf, Function<T, Number> fn) {
        collectSingle(tasks, cf, r -> {
            Number value = fn.apply(r);
            return toGauge(fqn, help, value, toLabels(), toValues());
        });
    }

    private <T> void collectSingle(List<CompletableFuture<List<Meter>>> tasks, CompletableFuture<T> cf, Function<T, Meter> fn) {
        collectList(tasks, cf, t -> Collections.singletonList(fn.apply(t)));
    }

    private <T> void collectList(List<CompletableFuture<List<Meter>>> tasks, CompletableFuture<T> cf, Function<T, List<Meter>> fn) {
        CompletableFuture<List<Meter>> task = cf.thenApplyAsync(fn, executor);
        tasks.add(task);
    }

    @Override
    public CollectorResult collect() {
        try (AdminHandle adminHandle = adminProvider.getAdminHandle()) {
            Admin admin = adminHandle.getAdmin();
            List<CompletableFuture<List<Meter>>> futures = new ArrayList<>();

            collectNumber(futures, fqn(null, "brokers"), "Number of Brokers in the Kafka Cluster.", toCF(admin.describeCluster().nodes()), Collection::size);

            CompletableFuture<Set<String>> topicsCS = toCF(admin.listTopics(new ListTopicsOptions().listInternal(true)).names());

            CompletableFuture<Map<String, TopicDescription>> descCS = topicsCS.thenComposeAsync(topics -> toCF(admin.describeTopics(filterTopics(topics)).all()), executor);
            collectList(futures, descCS, map -> {
                if (map.isEmpty()) {
                    return Collections.emptyList();
                }

                List<Meter> metrics = new ArrayList<>();

                String partitionsFqn = fqn("topic", "partitions");
                String replicasFqn = fqn("topic", "partition_replicas");
                String isrsFqn = fqn("topic", "partition_in_sync_replica");
                String leaderFqn = fqn("topic", "partition_leader");
                String preferredFqn = fqn("topic", "partition_leader_is_preferred");
                String underFqn = fqn("topic", "partition_under_replicated_partition");

                List<String> labelNames = toLabels("partition", "topic");
                for (Map.Entry<String, TopicDescription> entry : map.entrySet()) {
                    String topic = entry.getKey();
                    TopicDescription td = entry.getValue();
                    List<TopicPartitionInfo> partitions = td.partitions();

                    metrics.add(toGauge(partitionsFqn, "Number of partitions for this Topic", partitions.size(), toLabels("topic"), toValues(topic)));

                    for (TopicPartitionInfo tpi : partitions) {
                        Integer partition = tpi.partition();
                        List<Object> values = toValues(partition, topic);

                        List<Node> replicas = tpi.replicas();
                        metrics.add(toGauge(replicasFqn, "Number of Replicas for this Topic/Partition", replicas.size(), labelNames, values));

                        List<Node> isrs = tpi.isr();
                        metrics.add(toGauge(isrsFqn, "Number of In-Sync Replicas for this Topic/Partition", isrs.size(), labelNames, values));

                        Node leader = tpi.leader();
                        if (leader != null) {
                            int leaderId = leader.id();
                            metrics.add(toGauge(leaderFqn, "Leader Broker ID of this Topic/Partition", leaderId, labelNames, values));
                            metrics.add(toGauge(preferredFqn, "1 if Topic/Partition is using the Preferred Broker", replicas.size() > 0 && replicas.get(0).id() == leaderId ? 1 : 0, labelNames, values));
                            metrics.add(toGauge(underFqn, "1 if Topic/Partition is under Replicated", isrs.size() < replicas.size() ? 1 : 0, labelNames, values));
                        }
                    }
                }
                return metrics;
            });

            CompletableFuture<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> currentOffsets =
                offsets(admin, futures, fqn("topic", "partition_current_offset"), "Current Offset of a Broker at Topic/Partition", descCS, OffsetSpec.latest());
            offsets(admin, futures, fqn("topic", "partition_oldest_offset"), "Oldest Offset of a Broker at Topic/Partition", descCS, OffsetSpec.earliest());

            CompletableFuture<Collection<String>> groupIdsCF = toCF(groups.apply(admin.listConsumerGroups())).thenApplyAsync(this::toGroupIds, executor);

            CompletableFuture<Map<String, ConsumerGroupDescription>> cgdMapCF = groupIdsCF.thenComposeAsync(ids -> toCF(admin.describeConsumerGroups(ids).all()), executor);
            collectList(futures, cgdMapCF, map -> {
                List<Meter> metrics = new ArrayList<>();
                List<String> labelNames = toLabels("consumergroup");
                String fqn = fqn("consumergroup", "members");
                for (Map.Entry<String, ConsumerGroupDescription> entry : map.entrySet()) {
                    metrics.add(toGauge(fqn, "Amount of members in a consumer group", entry.getValue().members().size(), labelNames, toValues(entry.getKey())));
                }
                return metrics;
            });

            Map<String, Map<TopicPartition, OffsetAndMetadata>> groupOffsetsMap = new ConcurrentHashMap<>();
            CompletableFuture<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> cgOffsets = groupIdsCF.thenComposeAsync(ids -> {
                List<KafkaFuture<Void>> groupOffsetKFs = new ArrayList<>();
                for (String groupId : ids) {
                    KafkaFuture<Void> gKF = admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata()
                        .thenApply(m -> {
                            groupOffsetsMap.put(groupId, m);
                            return null;
                        });
                    groupOffsetKFs.add(gKF);
                }
                return toCF(KafkaFuture.allOf(groupOffsetKFs.toArray(new KafkaFuture[0]))).thenComposeAsync(v -> currentOffsets, executor);
            }, executor);
            collectList(futures, cgOffsets, map -> {
                List<Meter> metrics = new ArrayList<>();

                if (groupOffsetsMap.isEmpty()) {
                    return metrics;
                }

                List<String> labels2 = toLabels("consumergroup", "topic");
                List<String> labels3 = toLabels("consumergroup", "topic", "partition");

                String offsetFqn = fqn("consumergroup", "current_offset");
                String offsetSumFqn = fqn("consumergroup", "current_offset_sum");
                String lagFqn = fqn("consumergroup", "lag");
                String lagSumFqn = fqn("consumergroup", "lag_sum");

                for (Map.Entry<String, Map<TopicPartition, OffsetAndMetadata>> entry : groupOffsetsMap.entrySet()) {
                    String group = entry.getKey();
                    Map<TopicPartition, OffsetAndMetadata> offsetMap = entry.getValue();

                    Map<String, Long> topicOffsetSum = new HashMap<>();
                    for (Map.Entry<TopicPartition, OffsetAndMetadata> subEntry : offsetMap.entrySet()) {
                        String topic = subEntry.getKey().topic();
                        if (topicFilter.matcher(topic).matches()) {
                            long offset = subEntry.getValue().offset();
                            // Kafka will return -1 if there is no offset associated with a topic-partition under that consumer group
                            if (offset >= 0) {
                                topicOffsetSum.compute(topic, (t, o) -> o == null ? offset : o + offset);
                            }
                        }
                    }
                    topicOffsetSum.forEach((t, o) -> metrics.add(toGauge(offsetSumFqn, "Current Offset of a ConsumerGroup at Topic for all partitions", o, labels2, toValues(group, t))));

                    Map<String, Long> topicLagSum = new HashMap<>();
                    for (Map.Entry<TopicPartition, OffsetAndMetadata> subEntry : offsetMap.entrySet()) {
                        TopicPartition topicPartition = subEntry.getKey();
                        String topic = topicPartition.topic();
                        if (topicFilter.matcher(topic).matches()) {
                            int partition = topicPartition.partition();
                            long offset = subEntry.getValue().offset();
                            ListOffsetsResult.ListOffsetsResultInfo info = map.get(topicPartition);
                            if (info != null) {
                                // If the topic is consumed by that consumer group, but no offset associated with the partition
                                // forcing lag to -1 to be able to alert on that
                                long lag;
                                if (offset >= 0) {
                                    lag = info.offset() - offset;
                                    topicLagSum.compute(topic, (t, l) -> l == null ? lag : l + lag);
                                } else {
                                    lag = -1;
                                }
                                metrics.add(toGauge(lagFqn, "Current Approximate Lag of a ConsumerGroup at Topic/Partition", lag, labels3, toValues(group, topic, partition)));
                            }
                            metrics.add(toGauge(offsetFqn, "Current Offset of a ConsumerGroup at Topic/Partition", offset, labels3, toValues(group, topic, partition)));
                        }
                    }
                    topicLagSum.forEach((t, l) -> metrics.add(toGauge(lagSumFqn, "Current Approximate Lag of a ConsumerGroup at Topic for all partitions", l, labels2, toValues(group, t))));
                }
                return metrics;
            });

            return new CollectorResultImpl(futures);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CompletableFuture<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> offsets(
        Admin admin,
        List<CompletableFuture<List<Meter>>> futures,
        String fqn,
        String help,
        CompletableFuture<Map<String, TopicDescription>> descCS,
        OffsetSpec spec
    ) {
        CompletableFuture<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> offsetCS = descCS.thenComposeAsync(map -> {
            Map<TopicPartition, OffsetSpec> currentMap = new HashMap<>();
            for (TopicDescription td : map.values()) {
                for (TopicPartitionInfo tpi : td.partitions()) {
                    currentMap.put(new TopicPartition(td.name(), tpi.partition()), spec);
                }
            }
            return toCF(admin.listOffsets(currentMap).all());
        }, executor);
        collectList(futures, offsetCS, map -> {
            List<Meter> partitions = new ArrayList<>();
            List<String> labelNames = toLabels("partition", "topic");
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsetEntry : map.entrySet()) {
                Number offset = offsetEntry.getValue().offset();
                String topic = offsetEntry.getKey().topic();
                int partition = offsetEntry.getKey().partition();
                partitions.add(toGauge(fqn, help, offset, labelNames, toValues(partition, topic)));
            }
            return partitions;
        });
        return offsetCS;
    }
}
