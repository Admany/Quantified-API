package org.admany.quantified.core.common.resilience;

import org.admany.quantified.core.common.dev.DeveloperOverlayManager;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AutoHealthChecker {
    private static final Logger LOGGER = Logger.getLogger(AutoHealthChecker.class.getName());

    private static final AutoHealthChecker INSTANCE = new AutoHealthChecker();

    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final ConcurrentHashMap<String, HealthCheck> healthChecks = new ConcurrentHashMap<>();
    private volatile Instant lastHealthCheck = Instant.now();
    private volatile HealthStatus lastStatus = HealthStatus.HEALTHY;

    public enum HealthStatus {
        HEALTHY, DEGRADED, UNHEALTHY
    }

    private AutoHealthChecker() {
        initializeDefaultChecks();
        startHealthMonitoring();
    }

    public static AutoHealthChecker getInstance() {
        return INSTANCE;
    }

    private void initializeDefaultChecks() {
        registerHealthCheck("gpu_available", Duration.ofSeconds(30), () -> {
            try {
                return org.admany.quantified.core.common.opencl.core.OpenCLManager.hasExecutableRuntime();
            } catch (Exception e) {
                return false;
            }
        });

        registerHealthCheck("cache_healthy", Duration.ofMinutes(1), () -> {
            try {
                var inventory = org.admany.quantified.core.common.cache.CacheManager.inventory();
                return inventory.statsByName().values().stream()
                    .allMatch(stats -> stats.hitRate() > 0.1); // At least 10% hit rate
            } catch (Exception e) {
                return false;
            }
        });

        registerHealthCheck("queue_healthy", Duration.ofSeconds(10), () -> {
            try {
                var stats = org.admany.quantified.core.common.util.TaskScheduler.getStats();
                return stats.totalTasks() < 1000; // Reasonable queue limit
            } catch (Exception e) {
                return false;
            }
        });

        // Memory health check
        registerHealthCheck("memory_healthy", Duration.ofSeconds(30), () -> {
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            double usageRatio = (double) usedMemory / maxMemory;
            return usageRatio < 0.9; // Less than 90% memory usage
        });

        registerHealthCheck("mods_connected", Duration.ofMinutes(2), () -> {
            try {
                var mods = QuantifiedCoreRuntime.getRegisteredMods();
                return !mods.isEmpty();
            } catch (Exception e) {
                return false;
            }
        });
    }

    private void registerHealthCheck(String name, Duration interval, HealthCheck check) {
        healthChecks.put(name, check);
        LOGGER.fine("Registered auto health check: " + name + " (interval: " + interval + ")");
    }

    private void startHealthMonitoring() {
        CompletableFuture.delayedExecutor(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .execute(() -> {
                try {
                    performHealthChecks();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Health check cycle failed", e);
                } finally {
                    startHealthMonitoring(); // Schedule next cycle
                }
            });
    }

    private void performHealthChecks() {
        lastHealthCheck = Instant.now();

        int totalChecks = healthChecks.size();
        int passedChecks = 0;
        int failedChecks = 0;

        for (var entry : healthChecks.entrySet()) {
            String checkName = entry.getKey();
            HealthCheck check = entry.getValue();

            try {
                boolean result = check.check();
                if (result) {
                    passedChecks++;
                } else {
                    failedChecks++;
                    LOGGER.warning("Health check failed: " + checkName);
                    DeveloperOverlayManager.recordApiLog("[Health] Check failed: " + checkName);
                }
            } catch (Exception e) {
                failedChecks++;
                LOGGER.warning("Health check error for " + checkName + ": " + e.getMessage());
                DeveloperOverlayManager.recordApiLog("[Health] Check error: " + checkName + " - " + e.getMessage());
            }
        }

        HealthStatus newStatus;
        if (failedChecks == 0) {
            newStatus = HealthStatus.HEALTHY;
        } else if (failedChecks <= totalChecks / 3) {
            newStatus = HealthStatus.DEGRADED;
        } else {
            newStatus = HealthStatus.UNHEALTHY;
        }

        if (newStatus != lastStatus) {
            LOGGER.info("System health changed from " + lastStatus + " to " + newStatus +
                       " (" + passedChecks + "/" + totalChecks + " checks passed)");
            DeveloperOverlayManager.recordApiLog("[Health] Status: " + newStatus +
                                               " (" + passedChecks + "/" + totalChecks + " checks passed)");
            lastStatus = newStatus;
        }

        healthy.set(newStatus != HealthStatus.UNHEALTHY);
    }

    public boolean isHealthy() {
        return healthy.get();
    }

    public HealthReport getHealthReport() {
        return new HealthReport(
            lastStatus,
            lastHealthCheck,
            Duration.between(lastHealthCheck, Instant.now()),
            healthChecks.size()
        );
    }

    @FunctionalInterface
    private interface HealthCheck {
        boolean check() throws Exception;
    }

    public record HealthReport(
        HealthStatus status,
        Instant lastCheck,
        Duration timeSinceLastCheck,
        int totalChecks
    ) {}

    public void registerCustomHealthCheck(String name, Duration interval, HealthCheck check) {
        registerHealthCheck(name, interval, check);
    }
}
