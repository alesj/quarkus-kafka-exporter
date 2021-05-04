/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.utils;

import org.apache.kafka.common.KafkaFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/**
 * @author Ales Justin
 */
public class KafkaUtil {
    private static final Logger log = LoggerFactory.getLogger(KafkaUtil.class);

    public static <T> CompletableFuture<T> toCF(KafkaFuture<T> kf) {
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

    public static void applyContent(Properties properties, String key) {
        String property = properties.getProperty(key);
        if (property != null) {
            try {
                Path path = Paths.get(property);
                if (Files.exists(path)) {
                    String content = Files.readString(path);
                    properties.put(key, content);
                    log.info("Replaced property '{}' with file's content ...", key);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
