package io.strimzi.kafkaexporter.server.utils;

import org.apache.kafka.clients.admin.Admin;

/**
 * @author Ales Justin
 */
public interface AdminHandle extends AutoCloseable {
    Admin getAdmin();
}
