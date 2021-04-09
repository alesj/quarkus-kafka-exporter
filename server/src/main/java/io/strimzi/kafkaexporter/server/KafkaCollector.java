package io.strimzi.kafkaexporter.server;

import io.prometheus.client.Collector;
import io.prometheus.client.GaugeMetricFamily;
import io.quarkus.runtime.StartupEvent;
import org.apache.kafka.clients.admin.Admin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

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

    @Inject
    Admin admin;

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> mfs = new ArrayList<>();
        mfs.add(new GaugeMetricFamily("my_gauge", "help", 42));
        return mfs;
    }
}
