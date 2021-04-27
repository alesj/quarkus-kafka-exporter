package io.strimzi.kafkaexporter.server.test;

import io.strimzi.kafkaexporter.server.utils.AdminHandle;
import io.strimzi.kafkaexporter.server.utils.AdminProvider;
import io.strimzi.kafkaexporter.server.utils.InjectedProperties;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import org.apache.kafka.clients.admin.Admin;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import javax.inject.Inject;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/**
 * @author Ales Justin
 */
public class TestBase {
    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Inject
    AdminProvider adminProvider;

    @Inject
    Vertx vertx;

    @Inject
    @InjectedProperties("kafka")
    Properties properties;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "9308")
    int httpPort;

    protected boolean isKafkaRunning() {
        try (AdminHandle adminHandle = adminProvider.getAdminHandle()) {
            Admin admin = adminHandle.getAdmin();
            admin.listTopics().names().get();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @FunctionalInterface
    protected interface Logging {
        void log(Level level, String msg, Throwable t);
    }

    protected static Logging sysLogging = (l, m, t) -> {
        if (l == Level.INFO) {
            System.out.println(m);
        } else {
            System.err.println(m + ":" + t);
        }
    };

    protected Logging slf4jLogging = (l, m, t) -> {
        if (l == Level.INFO) {
            log.info(m);
        } else {
            log.error(m, t);
        }
    };

    protected void executeMetricsRequest(int retries, Logging logging) throws Exception {
        CountDownLatch latch = new CountDownLatch(retries);
        WebClient client = WebClient.create(vertx);
        try {
            while (retries > 0) {
                retries--;
                client.get(httpPort, "localhost", "/metrics").send(event -> {
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
}
