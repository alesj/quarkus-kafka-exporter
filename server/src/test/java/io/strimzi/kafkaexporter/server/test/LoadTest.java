package io.strimzi.kafkaexporter.server.test;

import io.quarkus.test.junit.QuarkusTest;
import io.strimzi.kafkaexporter.server.utils.AdminHandle;
import io.strimzi.kafkaexporter.server.utils.AdminProvider;
import io.strimzi.kafkaexporter.server.utils.InjectedProperties;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import javax.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Ales Justin
 */
@QuarkusTest
public class LoadTest {
    private static final Logger log = LoggerFactory.getLogger(LoadTest.class);

    @Inject
    AdminProvider adminProvider;

    @Inject
    Vertx vertx;

    @Inject
    @InjectedProperties("kafka")
    Properties properties;

    private boolean isKafkaRunning() {
        try (AdminHandle adminHandle = adminProvider.getAdminHandle()) {
            Admin admin = adminHandle.getAdmin();
            admin.listTopics().names().get();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void main(String[] args) {
        LoadTest test = new LoadTest();
        test.vertx = Vertx.vertx();
        try {
            Logging logging = (l, m, t) -> {
                if (l == Level.INFO) {
                    System.out.println(m);
                } else {
                    System.err.println(m + ":" + t);
                }
            };
            test.executeMetricsRequest(10, logging);
        } catch (Throwable t) {
            System.err.println("Error: " + t);
        } finally {
            test.vertx.close();
        }
    }

    @FunctionalInterface
    private interface Logging {
        void log(Level level, String msg, Throwable t);
    }

    private void executeMetricsRequest(int retries, Logging logging) throws Exception {
        CountDownLatch latch = new CountDownLatch(retries);
        WebClient client = WebClient.create(vertx);
        try {
            while (retries > 0) {
                retries--;
                client.get(9308, "localhost", "/metrics").send(event -> {
                    if (event.succeeded()) {
                        logging.log(Level.INFO, "Metrics: \n" + event.result().bodyAsString(), null);
                    } else {
                        logging.log(Level.ERROR, "Metrics request failed ...", event.cause());
                    }
                    latch.countDown();
                });
            }
            latch.await();
        } finally {
            client.close();
        }
    }

    static int nTopics = 50;
    static int nPartitions = 32;
    static int nGroups = 10;
    static int nConsumers = 16;
    static int nProducers = 5;
    static int nMsgs = 500;
    static long nTimeout = 60 * 1000; // how long we wait for msgs
    static int batch = 16;

    @BeforeEach
    public void init() throws Exception {
        Assumptions.assumeTrue(isKafkaRunning());

        List<Consumer<String, String>> consumers = new ArrayList<>();
        List<Producer<String, String>> producers = new ArrayList<>();

        try (AdminHandle adminHandle = adminProvider.getAdminHandle()) {
            Admin admin = adminHandle.getAdmin();
            Set<String> topics = admin.listTopics().names().get();

            for (int i = 0; i < nTopics / batch; i++) {
                Set<NewTopic> newTopics = new HashSet<>();
                // add topics in batches ...
                for (int j = batch * i; j < batch * (i + 1); j++) {
                    String topic = "test" + j;
                    if (!topics.contains(topic)) {
                        newTopics.add(new NewTopic(topic, nPartitions, (short) 1));
                    }
                }
                admin.createTopics(newTopics).all().get();
            }

            AtomicBoolean flag = new AtomicBoolean(true);
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < nGroups; i++) {
                Properties copy = new Properties();
                copy.putAll(properties);
                copy.put(ConsumerConfig.GROUP_ID_CONFIG, "group" + i);
                for (int j = 0; j < nConsumers; j++) {
                    Consumer<String, String> consumer = new KafkaConsumer<>(
                        copy, new StringDeserializer(), new StringDeserializer()
                    );
                    consumers.add(consumer);
                    consumer.subscribe(Collections.singleton("test" + ThreadLocalRandom.current().nextInt(nTopics)));
                    Thread thread = new Thread(() -> {
                        while (flag.get()) {
                            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                            if (records != null) {
                                records.forEach(c -> {
                                    System.out.println("Msg = " + c.value());
                                });
                            }
                            try {
                                Thread.sleep(100L);
                            } catch (InterruptedException ignored) {
                            }
                        }
                    });
                    threads.add(thread);
                    thread.start();
                }
            }
            for (int j = 0; j < nProducers; j++) {
                Producer<String, String> producer = new KafkaProducer<>(
                    properties, new StringSerializer(), new StringSerializer()
                );
                producers.add(producer);
                for (int k = 0; k < nMsgs; k++) {
                    producer.send(new ProducerRecord<>(
                        "test" + ThreadLocalRandom.current().nextInt(nTopics),
                        "Some msg " + j + "|" + k
                    ));
                }
            }
            Thread.sleep(nTimeout);
            flag.set(false);
            for (Thread thread : threads) {
                thread.join();
            }
        } finally {
            consumers.forEach(Consumer::close);
            producers.forEach(Producer::close);
        }
    }

    @Test
    public void testLoad() throws Exception {
        Assumptions.assumeTrue(isKafkaRunning());
        Logging logging = (l, m, t) -> {
            if (l == Level.INFO) {
                log.info(m);
            } else {
                log.error(m, t);
            }
        };
        executeMetricsRequest(5, logging);
    }
}
