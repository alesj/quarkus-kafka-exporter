package io.strimzi.kafkaexporter.server.test;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Ales Justin
 */
@QuarkusTest
public class SmokeTest extends TestBase {

    @Test
    public void testSimple() throws Exception {
        Assumptions.assumeTrue(isKafkaRunning());

        StringBuilder info = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Logging logging = (l, m, t) -> {
          if (t != null) {
            error.set(t);
          } else {
              info.append(m);
          }
        };

        executeMetricsRequest(1, logging);

        Assertions.assertNull(error.get());
        Assertions.assertTrue(info.toString().contains("Metrics:"));
    }
}
