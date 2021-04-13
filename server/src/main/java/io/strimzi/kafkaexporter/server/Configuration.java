package io.strimzi.kafkaexporter.server;

import io.strimzi.kafkaexporter.server.utils.AdminProvider;
import io.strimzi.kafkaexporter.server.utils.AdminProviderImpl;
import io.strimzi.kafkaexporter.server.utils.InjectedProperties;
import io.strimzi.kafkaexporter.server.utils.PropertiesUtil;
import org.apache.kafka.clients.admin.Admin;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.spi.InjectionPoint;
import java.util.Properties;

/**
 * @author Ales Justin
 */
public class Configuration {
    @Produces
    public Properties properties(InjectionPoint ip) {
        return PropertiesUtil.properties(ip);
    }

    @Produces
    @ApplicationScoped
    public AdminProvider createAdmin(@InjectedProperties("kafka") Properties properties) {
        return new AdminProviderImpl(properties);
    }

    public void closeAdmin(@Disposes Admin admin) {
        admin.close();
    }
}
