/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server;

import io.strimzi.kafkaexporter.server.utils.AdminProvider;
import io.strimzi.kafkaexporter.server.utils.AdminProviderImpl;
import io.strimzi.kafkaexporter.server.utils.InjectedProperties;
import io.strimzi.kafkaexporter.server.utils.KafkaUtil;
import io.strimzi.kafkaexporter.server.utils.PropertiesUtil;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.SslConfigs;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.spi.InjectionPoint;
import java.time.Duration;
import java.util.Properties;

/**
 * @author Ales Justin
 */
public class Configuration {

    @ConfigProperty(name = "refresh.metadata", defaultValue = "PT1M")
    Duration metadataRefreshInterval;

    @Produces
    public Properties properties(InjectionPoint ip) {
        return PropertiesUtil.properties(ip);
    }

    @Produces
    @ApplicationScoped
    public AdminProvider createAdmin(@InjectedProperties("kafka") Properties properties) {
        Properties copy = new Properties();
        copy.put(AdminClientConfig.METADATA_MAX_AGE_CONFIG, metadataRefreshInterval.toMillis());

        KafkaUtil.applyContent(properties, SslConfigs.SSL_KEYSTORE_KEY_CONFIG);
        KafkaUtil.applyContent(properties, SslConfigs.SSL_KEYSTORE_CERTIFICATE_CHAIN_CONFIG);
        KafkaUtil.applyContent(properties, SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG);

        copy.putAll(properties);
        return new AdminProviderImpl(copy);
    }

    public void closeAdmin(@Disposes AdminProvider adminProvider) throws Exception {
        adminProvider.close();
    }
}
