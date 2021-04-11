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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
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

    private static <T> CompletionStage<T> toCS(KafkaFuture<T> kf) {
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

    private <T> void collectSingle(CountDownLatch latch, List<MetricFamilySamples> mfs, String fqn, String help, CompletionStage<T> cs, Function<T, Number> fn) {
        collectList(latch, mfs, fqn, cs, r -> {
            Number value = fn.apply(r);
            return new GaugeMetricFamily(fqn, help, value.doubleValue());
        });
    }

    private <T> void collectList(CountDownLatch latch, List<MetricFamilySamples> mfs, String fqn, CompletionStage<T> cs, Function<T, MetricFamilySamples> fn) {
        cs.whenComplete((r, t) -> {
            if (t != null) {
                log.error("Error getting metrics for '{}'", fqn, t);
            } else {
                MetricFamilySamples sample = fn.apply(r);
                mfs.add(sample);
            }
            latch.countDown();
        });
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<MetricFamilySamples> collect() {
        int N = 4; // number of all metrics

        CountDownLatch latch = new CountDownLatch(N);
        List<MetricFamilySamples> mfs = new ArrayList<>(N);
        collectSingle(latch, mfs, fqn(null, "brokers"), "Number of Brokers in the Kafka Cluster.", toCS(admin.describeCluster().nodes()), Collection::size);

        CompletionStage<Set<String>> topicsCS = toCS(admin.listTopics().names());

        CompletionStage<Map<String, TopicDescription>> descCS = topicsCS.thenCompose(topics -> toCS(admin.describeTopics(topics).all()));
        collectList(latch, mfs, fqn("topic", "partitions"), descCS, map -> {
            GaugeMetricFamily topics = new GaugeMetricFamily(fqn("topic", "partitions"), "Number of partitions for this Topic", Collections.singletonList("topic"));
            for (Map.Entry<String, TopicDescription> entry : map.entrySet()) {
                String topic = entry.getKey();
                TopicDescription td = entry.getValue();
                List<TopicPartitionInfo> partitions = td.partitions();
                topics.addMetric(Collections.singletonList(topic), partitions.size());
            }
            return topics;
        });

        offsets(latch, mfs, fqn("topic", "partition_current_offset"), "Current Offset of a Broker at Topic/Partition", descCS, OffsetSpec.latest());
        offsets(latch, mfs, fqn("topic", "partition_oldest_offset"), "Oldest Offset of a Broker at Topic/Partition", descCS, OffsetSpec.earliest());

        await(latch);
        return mfs;
    }

    private void offsets(CountDownLatch latch, List<MetricFamilySamples> mfs, String fqn, String help, CompletionStage<Map<String, TopicDescription>> descCS, OffsetSpec spec) {
        CompletionStage<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> offsetCS = descCS.thenCompose(map -> {
            Map<TopicPartition, OffsetSpec> currentMap = new HashMap<>();
            for (TopicDescription td : map.values()) {
                for (TopicPartitionInfo tpi : td.partitions()) {
                    currentMap.put(new TopicPartition(td.name(), tpi.partition()), spec);
                }
            }
            return toCS(admin.listOffsets(currentMap).all());
        });
        collectList(latch, mfs, fqn, offsetCS, map -> {
            GaugeMetricFamily partitions = new GaugeMetricFamily(fqn, help, Arrays.asList("topic", "partition"));
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsetEntry : map.entrySet()) {
                Number offset = offsetEntry.getValue().offset();
                partitions.addMetric(Arrays.asList(offsetEntry.getKey().topic(), String.valueOf(offsetEntry.getKey().partition())), offset.doubleValue());
            }
            return partitions;
        });
    }
}
