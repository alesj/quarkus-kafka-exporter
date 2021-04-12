package io.strimzi.kafkaexporter.server.test;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.concurrent.CountDownLatch;

/**
 * @author Ales Justin
 */
@QuarkusTest
public class LoadTest {
    private static final Logger log = LoggerFactory.getLogger(LoadTest.class);

    @Inject
    Admin admin;

    @Inject
    Vertx vertx;

    private boolean isKafkaRunning() {
        try {
            admin.listTopics().names().get();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @BeforeEach
    public void init() {
        Assumptions.assumeTrue(isKafkaRunning());

        // TODO -- fill topics, consumer groups, etc
    }

    @Test
    public void testLoad() throws Exception {
        Assumptions.assumeTrue(isKafkaRunning());

        CountDownLatch latch = new CountDownLatch(1);
        WebClient client = WebClient.create(vertx);
        try {
            client.get(9308, "localhost", "/metrics").send(event -> {
                if (event.succeeded()) {
                    log.info(event.result().bodyAsString());
                } else {
                    log.error("Metrics request failed ...", event.cause());
                }
                latch.countDown();
            });
            latch.await();
        } finally {
            client.close();
        }
    }
}
