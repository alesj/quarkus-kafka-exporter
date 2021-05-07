/*
 * Copyright Red Hat inc.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafkaexporter.server.utils;

import java.util.concurrent.Executor;

/**
 * @author Ales Justin
 */
public class SmartExecutor implements Executor {
    private static final ThreadLocal<Boolean> IS_SMART_THREAD
        = ThreadLocal.withInitial(() -> false);

    private final Executor backgroundExecutor;

    public SmartExecutor(Executor backgroundExecutor) {
        this.backgroundExecutor = backgroundExecutor;
    }

    @Override
    public void execute(Runnable runnable) {
        if (IS_SMART_THREAD.get()) {
            runnable.run();
        } else {
            backgroundExecutor.execute(new SmartRunnable(runnable));
        }
    }

    private static class SmartRunnable implements Runnable {
        private final Runnable runnable;

        SmartRunnable(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void run() {
            IS_SMART_THREAD.set(true);
            try {
                runnable.run();
            } finally {
                IS_SMART_THREAD.set(false);
            }
        }
    }
}