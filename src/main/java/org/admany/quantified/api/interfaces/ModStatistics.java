package org.admany.quantified.api.interfaces;

public interface ModStatistics {
    String getModId();
    String getModVersion();
    java.time.Instant getLastActivity();
    long getTotalTasksSubmitted();
    long getTasksCompleted();
    long getTasksFailed();
    int getCurrentQueueDepth();
    boolean isThrottled();
    double getThrottleFactor();
    java.time.Duration getAverageTaskTime();
    java.time.Duration getMaxTaskTime();
    double getTasksPerSecond();
    double getCacheHitRate();
    long getCacheSize();
    long getCacheMaxSize();
    long getCacheEvictions();
    long getCacheMemoryUsage();
    long getPacketsSent();
    long getPacketsReceived();
    long getNetworkErrors();
    long getNetworkBytesTransferred();
    java.time.Duration getTotalGPUTime();
    long getPeakVRAMUsage();
    double getGPUUtilization();
    double getCPUFallbackRate();
}