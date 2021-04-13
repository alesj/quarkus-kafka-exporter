package io.strimzi.kafkaexporter.server.test;

import io.quarkus.test.junit.QuarkusTest;
import io.strimzi.kafkaexporter.server.utils.AdminHandle;
import io.strimzi.kafkaexporter.server.utils.AdminProvider;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import javax.inject.Inject;
import java.util.concurrent.CountDownLatch;

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

    @BeforeEach
    public void init() {
        Assumptions.assumeTrue(isKafkaRunning());

        // TODO -- fill topics, consumer groups, etc
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
        executeMetricsRequest(1, logging);
    }
}
