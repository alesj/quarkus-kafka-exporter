package io.strimzi.kafkaexporter.server;

import io.prometheus.client.Collector;
import io.prometheus.client.GaugeMetricFamily;
import io.quarkus.runtime.StartupEvent;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
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
import java.util.function.Function;

/**
 * @author Ales Justin
 */
@ApplicationScoped
public class KafkaCollector extends Collector {
    private static final Logger log = LoggerFactory.getLogger(KafkaCollector.class);

    public void init(@Observes StartupEvent event) {
        log.info("Registered " + KafkaCollector.class.getSimpleName() + " ...");
        register();
    }

    @ConfigProperty(name = "namespace", defaultValue = "kafka")
    String namespace;

    @Inject
    Admin admin;

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

    private <T> void collectSingle(List<CompletableFuture<?>> tasks, List<MetricFamilySamples> mfs, String fqn, String help, CompletableFuture<T> cf, Function<T, Number> fn) {
        collectList(tasks, mfs, fqn, cf, r -> {
            Number value = fn.apply(r);
            return new GaugeMetricFamily(fqn, help, value.doubleValue());
        });
    }

    private <T> void collectList(List<CompletableFuture<?>> tasks, List<MetricFamilySamples> mfs, String fqn, CompletableFuture<T> cf, Function<T, MetricFamilySamples> fn) {
        CompletableFuture<T> task = cf.whenComplete((r, t) -> {
            if (t != null) {
                log.error("Error getting metrics for '{}'", fqn, t);
            } else {
                MetricFamilySamples sample = fn.apply(r);
                mfs.add(sample);
            }
        });
        tasks.add(task);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<CompletableFuture<?>> futures = new ArrayList<>();

        List<MetricFamilySamples> mfs = new ArrayList<>();
        collectSingle(futures, mfs, fqn(null, "brokers"), "Number of Brokers in the Kafka Cluster.", toCF(admin.describeCluster().nodes()), Collection::size);

        CompletableFuture<Set<String>> topicsCS = toCF(admin.listTopics().names());

        CompletableFuture<Map<String, TopicDescription>> descCS = topicsCS.thenCompose(topics -> toCF(admin.describeTopics(topics).all()));
        collectList(futures, mfs, fqn("topic", "partitions"), descCS, map -> {
            GaugeMetricFamily topics = new GaugeMetricFamily(fqn("topic", "partitions"), "Number of partitions for this Topic", Collections.singletonList("topic"));
            for (Map.Entry<String, TopicDescription> entry : map.entrySet()) {
                String topic = entry.getKey();
                TopicDescription td = entry.getValue();
                List<TopicPartitionInfo> partitions = td.partitions();
                topics.addMetric(Collections.singletonList(topic), partitions.size());
            }
            return topics;
        });

        offsets(futures, mfs, fqn("topic", "partition_current_offset"), "Current Offset of a Broker at Topic/Partition", descCS, OffsetSpec.latest());
        offsets(futures, mfs, fqn("topic", "partition_oldest_offset"), "Oldest Offset of a Broker at Topic/Partition", descCS, OffsetSpec.earliest());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return mfs;
    }

    private void offsets(List<CompletableFuture<?>> futures, List<MetricFamilySamples> mfs, String fqn, String help, CompletableFuture<Map<String, TopicDescription>> descCS, OffsetSpec spec) {
        CompletableFuture<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> offsetCS = descCS.thenCompose(map -> {
            Map<TopicPartition, OffsetSpec> currentMap = new HashMap<>();
            for (TopicDescription td : map.values()) {
                for (TopicPartitionInfo tpi : td.partitions()) {
                    currentMap.put(new TopicPartition(td.name(), tpi.partition()), spec);
                }
            }
            return toCF(admin.listOffsets(currentMap).all());
        });
        collectList(futures, mfs, fqn, offsetCS, map -> {
            GaugeMetricFamily partitions = new GaugeMetricFamily(fqn, help, Arrays.asList("topic", "partition"));
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsetEntry : map.entrySet()) {
                Number offset = offsetEntry.getValue().offset();
                partitions.addMetric(Arrays.asList(offsetEntry.getKey().topic(), String.valueOf(offsetEntry.getKey().partition())), offset.doubleValue());
            }
            return partitions;
        });
    }
}
