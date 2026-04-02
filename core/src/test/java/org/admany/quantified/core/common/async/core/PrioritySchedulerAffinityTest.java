package org.admany.quantified.core.common.async.core;

import org.admany.quantified.core.common.async.task.PriorityTask;
import org.admany.quantified.core.common.async.task.PriorityTaskType;
import org.admany.quantified.core.common.async.task.TaskMetadata;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PrioritySchedulerAffinityTest {

    @Test
    void sameAffinityTasksStayOnSameForegroundWorkerLane() throws InterruptedException {
        PriorityScheduler scheduler = new PriorityScheduler(
            2,
            1,
            2,
            1,
            Duration.ofMillis(100),
            256
        );
        scheduler.start();
        try {
            CountDownLatch latch = new CountDownLatch(3);
            Set<String> workerNames = ConcurrentHashMap.newKeySet();
            TaskMetadata metadata = TaskMetadata.builder()
                .batchable(true)
                .affinityKey("graph|test|region_1_1")
                .build();

            for (int i = 0; i < 3; i++) {
                long taskKey = 10_000L + i;
                scheduler.submit(new PriorityTask(
                    taskKey,
                    PriorityTaskType.BUILDING,
                    PriorityTaskType.BUILDING.defaultScore(),
                    () -> {
                        workerNames.add(Thread.currentThread().getName());
                        latch.countDown();
                    },
                    metadata,
                    "graph_test"
                ));
            }

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(workerNames).hasSize(1);
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void schedulerCanScaleBeyondInitialForegroundWorkerCount() throws Exception {
        PriorityScheduler scheduler = new PriorityScheduler(
            1,
            1,
            4,
            2,
            Duration.ofMillis(100),
            256
        );
        scheduler.start();
        try {
            setDesiredWorkers(scheduler, "desiredForegroundWorkers", 4);

            CountDownLatch done = new CountDownLatch(4);
            Set<String> workerNames = ConcurrentHashMap.newKeySet();
            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger peakConcurrent = new AtomicInteger();

            for (int i = 0; i < 4; i++) {
                long taskKey = 20_000L + i;
                scheduler.submit(new PriorityTask(
                    taskKey,
                    PriorityTaskType.FOREGROUND,
                    PriorityTaskType.FOREGROUND.defaultScore(),
                    () -> {
                        workerNames.add(Thread.currentThread().getName());
                        int active = concurrent.incrementAndGet();
                        peakConcurrent.updateAndGet(previous -> Math.max(previous, active));
                        try {
                            Thread.sleep(150L);
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                        } finally {
                            concurrent.decrementAndGet();
                            done.countDown();
                        }
                    },
                    TaskMetadata.DEFAULT,
                    "scale_test"
                ));
            }

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(workerNames.size()).isGreaterThan(1);
            assertThat(peakConcurrent.get()).isGreaterThan(1);
        } finally {
            scheduler.stop();
        }
    }

    private static void setDesiredWorkers(PriorityScheduler scheduler, String fieldName, int value) throws Exception {
        Field field = PriorityScheduler.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        AtomicInteger target = (AtomicInteger) field.get(scheduler);
        target.set(value);
    }
}
