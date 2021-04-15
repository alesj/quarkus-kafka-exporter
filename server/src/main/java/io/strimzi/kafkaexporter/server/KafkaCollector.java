package io.strimzi.kafkaexporter.server;

import io.prometheus.client.Collector;
import io.prometheus.client.GaugeMetricFamily;
import io.quarkus.runtime.StartupEvent;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author Ales Justin
 */
@ApplicationScoped
public class KafkaCollector extends Collector implements Collector.Describable {
    private static final Logger log = LoggerFactory.getLogger(KafkaCollector.class);

    public void init(@Observes StartupEvent event) {
        log.info("Registered " + KafkaCollector.class.getSimpleName() + " ...");
        register();
    }

    @ConfigProperty(name = "namespace", defaultValue = "kafka")
    String namespace;

    @ConfigProperty(name = "topic.filter", defaultValue = ".*")
    Pattern topicFilter;

    @ConfigProperty(name = "group.filter", defaultValue = ".*")
    Pattern groupFilter;

    @Inject
    AdminProvider adminProvider;

    private static <T> CompletableFuture<T> toCF(KafkaFuture<T> kf) {
        CompletableFuture<T> cf = new CompletableFuture<>();
        kf.whenComplete((v, t) -> {
            if (t != null) {
                cf.completeExceptionally(t);
            } else {
                cf.complete(v);
            }
        });
        return cf;
    }

    private String fqn(String system, String name) {
        if (namespace != null && system != null) {
            return namespace + "_" + system + "_" + name;
        }
        if (system != null) {
            return system + "_" + name;
        }
        if (namespace != null) {
            return namespace + "_" + name;
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

    private <T> void collectNumber(List<CompletableFuture<?>> tasks, List<MetricFamilySamples> mfs, String fqn, String help, CompletableFuture<T> cf, Function<T, Number> fn) {
        collectSingle(tasks, mfs, fqn, cf, r -> {
            Number value = fn.apply(r);
            return new GaugeMetricFamily(fqn, help, value.doubleValue());
        });
    }

    private <T> void collectSingle(List<CompletableFuture<?>> tasks, List<MetricFamilySamples> mfs, String fqn, CompletableFuture<T> cf, Function<T, MetricFamilySamples> fn) {
        collectList(tasks, mfs, fqn, cf, t -> Collections.singletonList(fn.apply(t)));
    }

    private <T> void collectList(List<CompletableFuture<?>> tasks, List<MetricFamilySamples> mfs, String fqn, CompletableFuture<T> cf, Function<T, List<MetricFamilySamples>> fn) {
        CompletableFuture<T> task = cf.whenComplete((r, t) -> {
            if (t != null) {
                log.error("Error getting metrics for '{}'", fqn, t);
            } else {
                List<MetricFamilySamples> samples = fn.apply(r);
                mfs.addAll(samples);
            }
        });
        tasks.add(task);
    }

    @Override
    public List<MetricFamilySamples> describe() {
        // we use single Collector, so we should be fine - no collisions
        // this then allows for lazy Admin init
        return Collections.emptyList();
    }

    @Override
    public List<MetricFamilySamples> collect() {
        try (AdminHandle adminHandle = adminProvider.getAdminHandle()) {
            Admin admin = adminHandle.getAdmin();
            List<CompletableFuture<?>> futures = new ArrayList<>();

            List<MetricFamilySamples> mfs = new ArrayList<>();
            collectNumber(futures, mfs, fqn(null, "brokers"), "Number of Brokers in the Kafka Cluster.", toCF(admin.describeCluster().nodes()), Collection::size);

            CompletableFuture<Set<String>> topicsCS = toCF(admin.listTopics(new ListTopicsOptions().listInternal(true)).names());

            CompletableFuture<Map<String, TopicDescription>> descCS = topicsCS.thenCompose(topics -> toCF(admin.describeTopics(filterTopics(topics)).all()));
            collectList(futures, mfs, fqn("topic", "partitions"), descCS, map -> {
                if (map.isEmpty()) {
                    return Collections.emptyList();
                }

                GaugeMetricFamily topics = new GaugeMetricFamily(fqn("topic", "partitions"), "Number of partitions for this Topic", Collections.singletonList("topic"));
                List<String> labelNames = Arrays.asList("partition", "topic");
                GaugeMetricFamily leaders = new GaugeMetricFamily(fqn("topic", "partition_leader"), "Leader Broker ID of this Topic/Partition", labelNames);
                GaugeMetricFamily nReplicas = new GaugeMetricFamily(fqn("topic", "partition_replicas"), "Number of Replicas for this Topic/Partition", labelNames);
                GaugeMetricFamily nIsrs = new GaugeMetricFamily(fqn("topic", "partition_in_sync_replica"), "Number of In-Sync Replicas for this Topic/Partition", labelNames);
                GaugeMetricFamily preferred = new GaugeMetricFamily(fqn("topic", "partition_leader_is_preferred"), "1 if Topic/Partition is using the Preferred Broker", labelNames);
                GaugeMetricFamily under = new GaugeMetricFamily(fqn("topic", "partition_under_replicated_partition"), "1 if Topic/Partition is under Replicated", labelNames);
                for (Map.Entry<String, TopicDescription> entry : map.entrySet()) {
                    String topic = entry.getKey();
                    TopicDescription td = entry.getValue();
                    List<TopicPartitionInfo> partitions = td.partitions();
                    topics.addMetric(Collections.singletonList(topic), partitions.size());
                    for (TopicPartitionInfo tpi : partitions) {
                        String partition = String.valueOf(tpi.partition());
                        List<String> labels = Arrays.asList(partition, topic);

                        List<Node> replicas = tpi.replicas();
                        nReplicas.addMetric(labels, replicas.size());

                        List<Node> isrs = tpi.isr();
                        nIsrs.addMetric(labels, isrs.size());

                        Node leader = tpi.leader();
                        if (leader != null) {
                            int leaderId = leader.id();
                            leaders.addMetric(labels, leaderId);
                            preferred.addMetric(labels, replicas.size() > 0 && replicas.get(0).id() == leaderId ? 1 : 0);
                            under.addMetric(labels, isrs.size() < replicas.size() ? 1 : 0);
                        }
                    }
                }
                return Arrays.asList(topics, leaders, nReplicas, nIsrs, preferred, under);
            });

            CompletableFuture<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> currentOffsets =
                offsets(admin, futures, mfs, fqn("topic", "partition_current_offset"), "Current Offset of a Broker at Topic/Partition", descCS, OffsetSpec.latest());
            offsets(admin, futures, mfs, fqn("topic", "partition_oldest_offset"), "Oldest Offset of a Broker at Topic/Partition", descCS, OffsetSpec.earliest());

            // only valid ?
            CompletableFuture<Collection<String>> groupIdsCF = toCF(admin.listConsumerGroups().valid()).thenApply(this::toGroupIds);

            CompletableFuture<Map<String, ConsumerGroupDescription>> cgdMapCF = groupIdsCF.thenCompose(ids -> toCF(admin.describeConsumerGroups(ids).all()));
            collectSingle(futures, mfs, fqn("consumergroup", "members"), cgdMapCF, map -> {
                GaugeMetricFamily groups = new GaugeMetricFamily(fqn("consumergroup", "members"), "Amount of members in a consumer group", Collections.singletonList("consumergroup"));
                for (Map.Entry<String, ConsumerGroupDescription> entry : map.entrySet()) {
                    groups.addMetric(Collections.singletonList(entry.getKey()), entry.getValue().members().size());
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
            collectList(futures, mfs, fqn("consumergroup", "current_offset"), cgOffsets, map -> {
                List<MetricFamilySamples> metrics = new ArrayList<>();

                if (groupOffsetsMap.isEmpty()) {
                    return metrics;
                }

                GaugeMetricFamily groupOffsets = new GaugeMetricFamily(fqn("consumergroup", "current_offset"), "Current Offset of a ConsumerGroup at Topic/Partition", Arrays.asList("consumergroup", "topic", "partition"));
                metrics.add(groupOffsets);
                GaugeMetricFamily groupOffsetsSum = new GaugeMetricFamily(fqn("consumergroup", "current_offset_sum"), "Current Offset of a ConsumerGroup at Topic for all partitions", Arrays.asList("consumergroup", "topic"));
                metrics.add(groupOffsetsSum);
                GaugeMetricFamily groupLags = new GaugeMetricFamily(fqn("consumergroup", "lag"), "Current Approximate Lag of a ConsumerGroup at Topic/Partition", Arrays.asList("consumergroup", "topic", "partition"));
                metrics.add(groupLags);
                GaugeMetricFamily groupLagSum = new GaugeMetricFamily(fqn("consumergroup", "lag_sum"), "Current Approximate Lag of a ConsumerGroup at Topic for all partitions", Arrays.asList("consumergroup", "topic"));
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
                    topicOffsetSum.forEach((t, o) -> groupOffsetsSum.addMetric(Arrays.asList(group, t), o));

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
                                groupLags.addMetric(Arrays.asList(group, topic, String.valueOf(partition)), lag);
                            }
                            groupOffsets.addMetric(Arrays.asList(group, topic, String.valueOf(partition)), offset);
                        }
                    }
                    topicLagSum.forEach((t, l) -> groupLagSum.addMetric(Arrays.asList(group, t), l));
                }
                return metrics;
            });

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            return mfs;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CompletableFuture<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> offsets(Admin admin, List<CompletableFuture<?>> futures, List<MetricFamilySamples> mfs, String fqn, String help, CompletableFuture<Map<String, TopicDescription>> descCS, OffsetSpec spec) {
        CompletableFuture<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> offsetCS = descCS.thenCompose(map -> {
            Map<TopicPartition, OffsetSpec> currentMap = new HashMap<>();
            for (TopicDescription td : map.values()) {
                for (TopicPartitionInfo tpi : td.partitions()) {
                    currentMap.put(new TopicPartition(td.name(), tpi.partition()), spec);
                }
            }
            return toCF(admin.listOffsets(currentMap).all());
        });
        collectSingle(futures, mfs, fqn, offsetCS, map -> {
            GaugeMetricFamily partitions = new GaugeMetricFamily(fqn, help, Arrays.asList("partition", "topic"));
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsetEntry : map.entrySet()) {
                Number offset = offsetEntry.getValue().offset();
                String topic = offsetEntry.getKey().topic();
                int partition = offsetEntry.getKey().partition();
                partitions.addMetric(Arrays.asList(String.valueOf(partition), topic), offset.doubleValue());
            }
            return partitions;
        });
        return offsetCS;
    }
}
