package io.strimzi.kafkaexporter.server.utils;

import org.apache.kafka.clients.admin.Admin;

import java.util.LinkedList;
import java.util.Properties;
import java.util.Queue;

/**
 * @author Ales Justin
 */
public class AdminProviderImpl implements AdminProvider {
    private final Queue<Admin> admins = new LinkedList<>();
    private final Properties properties;

    public AdminProviderImpl(Properties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized AdminHandle getAdminHandle() {
        Admin admin = admins.poll();
        if (admin == null) {
            admin = Admin.create(properties);
        }
        Admin finalAdmin = admin;
        return new AdminHandle() {
            @Override
            public Admin getAdmin() {
                return finalAdmin;
            }

            @Override
            public void close() {
                synchronized (AdminProviderImpl.this) {
                    admins.add(finalAdmin);
                }
            }
        };
    }

    @Override
    public void close() {
        admins.forEach(Admin::close);
    }
}
