/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server;

import io.prometheus.client.Collector;
import io.prometheus.client.GaugeMetricFamily;
import io.strimzi.kafkaexporter.server.utils.AdminHandle;
import io.strimzi.kafkaexporter.server.utils.AdminProvider;
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

    @ConfigProperty(name = "kafka.labels")
    Optional<String> kafkaLabels;

    private Map<String, String> labels;

    @Inject
    AdminProvider adminProvider;

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

    private List<String> toLabels(String... ls) {
        List<String> result = new ArrayList<>();
        result.addAll(getLabels().keySet());
        result.addAll(Arrays.asList(ls));
        return result;
    }

    private List<String> toValues(Object... vs) {
        List<String> result = new ArrayList<>();
        result.addAll(getLabels().values());
        result.addAll(Arrays.stream(vs).map(String::valueOf).collect(Collectors.toList()));
        return result;
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

    private <T> void collectNumber(List<CompletableFuture<List<Collector.MetricFamilySamples>>> tasks, String fqn, String help, CompletableFuture<T> cf, Function<T, Number> fn) {
        collectSingle(tasks, cf, r -> {
            Number value = fn.apply(r);
            GaugeMetricFamily gauge = new GaugeMetricFamily(fqn, help, toLabels());
            gauge.addMetric(toValues(), value.doubleValue());
            return gauge;
        });
    }

    private <T> void collectSingle(List<CompletableFuture<List<Collector.MetricFamilySamples>>> tasks, CompletableFuture<T> cf, Function<T, Collector.MetricFamilySamples> fn) {
        collectList(tasks, cf, t -> Collections.singletonList(fn.apply(t)));
    }

    private <T> void collectList(List<CompletableFuture<List<Collector.MetricFamilySamples>>> tasks, CompletableFuture<T> cf, Function<T, List<Collector.MetricFamilySamples>> fn) {
        CompletableFuture<List<Collector.MetricFamilySamples>> task = cf.thenApply(fn);
        tasks.add(task);
    }

    @Override
    public CollectorResult collect() {
        try (AdminHandle adminHandle = adminProvider.getAdminHandle()) {
            Admin admin = adminHandle.getAdmin();
            List<CompletableFuture<List<Collector.MetricFamilySamples>>> futures = new ArrayList<>();

            collectNumber(futures, fqn(null, "brokers"), "Number of Brokers in the Kafka Cluster.", toCF(admin.describeCluster().nodes()), Collection::size);

            CompletableFuture<Set<String>> topicsCS = toCF(admin.listTopics(new ListTopicsOptions().listInternal(true)).names());

            CompletableFuture<Map<String, TopicDescription>> descCS = topicsCS.thenCompose(topics -> toCF(admin.describeTopics(filterTopics(topics)).all()));
            collectList(futures, descCS, map -> {
                if (map.isEmpty()) {
                    return Collections.emptyList();
                }

                GaugeMetricFamily topics = new GaugeMetricFamily(fqn("topic", "partitions"), "Number of partitions for this Topic", toLabels("topic"));
                List<String> labelNames = toLabels("partition", "topic");
                GaugeMetricFamily leaders = new GaugeMetricFamily(fqn("topic", "partition_leader"), "Leader Broker ID of this Topic/Partition", labelNames);
                GaugeMetricFamily nReplicas = new GaugeMetricFamily(fqn("topic", "partition_replicas"), "Number of Replicas for this Topic/Partition", labelNames);
                GaugeMetricFamily nIsrs = new GaugeMetricFamily(fqn("topic", "partition_in_sync_replica"), "Number of In-Sync Replicas for this Topic/Partition", labelNames);
                GaugeMetricFamily preferred = new GaugeMetricFamily(fqn("topic", "partition_leader_is_preferred"), "1 if Topic/Partition is using the Preferred Broker", labelNames);
                GaugeMetricFamily under = new GaugeMetricFamily(fqn("topic", "partition_under_replicated_partition"), "1 if Topic/Partition is under Replicated", labelNames);
                for (Map.Entry<String, TopicDescription> entry : map.entrySet()) {
                    String topic = entry.getKey();
                    TopicDescription td = entry.getValue();
                    List<TopicPartitionInfo> partitions = td.partitions();
                    topics.addMetric(toValues(topic), partitions.size());
                    for (TopicPartitionInfo tpi : partitions) {
                        Integer partition = tpi.partition();
                        List<String> values = toValues(partition, topic);

                        List<Node> replicas = tpi.replicas();
                        nReplicas.addMetric(values, replicas.size());

                        List<Node> isrs = tpi.isr();
                        nIsrs.addMetric(values, isrs.size());

                        Node leader = tpi.leader();
                        if (leader != null) {
                            int leaderId = leader.id();
                            leaders.addMetric(values, leaderId);
                            preferred.addMetric(values, replicas.size() > 0 && replicas.get(0).id() == leaderId ? 1 : 0);
                            under.addMetric(values, isrs.size() < replicas.size() ? 1 : 0);
                        }
                    }
                }
                return Arrays.asList(topics, leaders, nReplicas, nIsrs, preferred, under);
            });

            CompletableFuture<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> currentOffsets =
                offsets(admin, futures, fqn("topic", "partition_current_offset"), "Current Offset of a Broker at Topic/Partition", descCS, OffsetSpec.latest());
            offsets(admin, futures, fqn("topic", "partition_oldest_offset"), "Oldest Offset of a Broker at Topic/Partition", descCS, OffsetSpec.earliest());

            // only valid ?
            CompletableFuture<Collection<String>> groupIdsCF = toCF(admin.listConsumerGroups().valid()).thenApply(this::toGroupIds);

            CompletableFuture<Map<String, ConsumerGroupDescription>> cgdMapCF = groupIdsCF.thenCompose(ids -> toCF(admin.describeConsumerGroups(ids).all()));
            collectSingle(futures, cgdMapCF, map -> {
                GaugeMetricFamily groups = new GaugeMetricFamily(fqn("consumergroup", "members"), "Amount of members in a consumer group", toLabels("consumergroup"));
                for (Map.Entry<String, ConsumerGroupDescription> entry : map.entrySet()) {
                    groups.addMetric(toValues(entry.getKey()), entry.getValue().members().size());
                }
                return groups;
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
                return toCF(KafkaFuture.allOf(groupOffsetKFs.toArray(new KafkaFuture[0]))).thenCompose(v -> currentOffsets);
            });
            collectList(futures, cgOffsets, map -> {
                List<Collector.MetricFamilySamples> metrics = new ArrayList<>();

                if (groupOffsetsMap.isEmpty()) {
                    return metrics;
                }

                GaugeMetricFamily groupOffsets = new GaugeMetricFamily(fqn("consumergroup", "current_offset"), "Current Offset of a ConsumerGroup at Topic/Partition", toLabels("consumergroup", "topic", "partition"));
                metrics.add(groupOffsets);
                GaugeMetricFamily groupOffsetsSum = new GaugeMetricFamily(fqn("consumergroup", "current_offset_sum"), "Current Offset of a ConsumerGroup at Topic for all partitions", toLabels("consumergroup", "topic"));
                metrics.add(groupOffsetsSum);
                GaugeMetricFamily groupLags = new GaugeMetricFamily(fqn("consumergroup", "lag"), "Current Approximate Lag of a ConsumerGroup at Topic/Partition", toLabels("consumergroup", "topic", "partition"));
                metrics.add(groupLags);
                GaugeMetricFamily groupLagSum = new GaugeMetricFamily(fqn("consumergroup", "lag_sum"), "Current Approximate Lag of a ConsumerGroup at Topic for all partitions", toLabels("consumergroup", "topic"));
                metrics.add(groupLagSum);

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
                    topicOffsetSum.forEach((t, o) -> groupOffsetsSum.addMetric(toValues(group, t), o));

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
                                groupLags.addMetric(toValues(group, topic, String.valueOf(partition)), lag);
                            }
                            groupOffsets.addMetric(toValues(group, topic, String.valueOf(partition)), offset);
                        }
                    }
                    topicLagSum.forEach((t, l) -> groupLagSum.addMetric(toValues(group, t), l));
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
        List<CompletableFuture<List<Collector.MetricFamilySamples>>> futures,
        String fqn,
        String help,
        CompletableFuture<Map<String, TopicDescription>> descCS,
        OffsetSpec spec
    ) {
        CompletableFuture<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> offsetCS = descCS.thenCompose(map -> {
            Map<TopicPartition, OffsetSpec> currentMap = new HashMap<>();
            for (TopicDescription td : map.values()) {
                for (TopicPartitionInfo tpi : td.partitions()) {
                    currentMap.put(new TopicPartition(td.name(), tpi.partition()), spec);
                }
            }
            return toCF(admin.listOffsets(currentMap).all());
        });
        collectSingle(futures, offsetCS, map -> {
            GaugeMetricFamily partitions = new GaugeMetricFamily(fqn, help, toLabels("partition", "topic"));
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsetEntry : map.entrySet()) {
                Number offset = offsetEntry.getValue().offset();
                String topic = offsetEntry.getKey().topic();
                int partition = offsetEntry.getKey().partition();
                partitions.addMetric(toValues(partition, topic), offset.doubleValue());
            }
            return partitions;
        });
        return offsetCS;
    }
}
