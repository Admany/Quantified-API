package org.admany.quantified.core.common.config;

import org.admany.quantified.core.common.parallel.config.ParallelConfig;
import org.admany.quantified.core.common.parallel.policy.ParallelFailurePolicy;
import org.admany.quantified.core.common.util.QuantifiedPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MultithreadingConfigTest {
    @TempDir
    Path gameDir;

    private MultithreadingConfig.Config previousConfig;
    private Logger previousLogger;
    private QuantifiedPaths.PathProvider previousPaths;
    private final Logger logger = mock(Logger.class);

    @BeforeEach
    void setUp() throws Exception {
        previousConfig = MultithreadingConfig.CONFIG;
        previousLogger = MultithreadingConfig.LOGGER;
        var field = QuantifiedPaths.class.getDeclaredField("PATH_PROVIDER");
        field.setAccessible(true);
        previousPaths = (QuantifiedPaths.PathProvider) field.get(null);
        QuantifiedPaths.setPathProvider(new QuantifiedPaths.PathProvider() {
            public Path getGameDir() { return gameDir; }
            public Path getConfigDir() { return gameDir.resolve("config"); }
        });
        MultithreadingConfig.LOGGER = logger;
        Files.createDirectories(QuantifiedPaths.getConfigDir());
    }

    @AfterEach
    void tearDown() {
        MultithreadingConfig.CONFIG = previousConfig;
        MultithreadingConfig.LOGGER = previousLogger;
        QuantifiedPaths.setPathProvider(previousPaths);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"parallelMaxThreads\":2,\"parallelQueueLimit\":1024,\"parallelMaxSlicesPerMod\":7,\"parallelFailurePolicy\":\"BEST_EFFORT\"}",
        "{\"parallelMaxThreads\":{\"value\":2},\"parallelQueueLimit\":{\"value\":1024},\"parallelMaxSlicesPerMod\":{\"value\":7},\"parallelFailurePolicy\":{\"value\":\"BEST_EFFORT\"}}",
        "{\"general\":{},\"parallelMaxThreads\":2,\"parallelQueueLimit\":1024,\"parallelMaxSlicesPerMod\":7,\"parallelFailurePolicy\":\"BEST_EFFORT\"}",
        "{\"parallel\":{\"parallelMaxThreads\":{\"value\":2},\"parallelQueueLimit\":{\"value\":1024},\"parallelMaxSlicesPerMod\":{\"value\":7},\"parallelFailurePolicy\":{\"value\":\"BEST_EFFORT\"}}}"
    })
    void editedParallelSettingsSurviveLoadingAndSaving(String json) throws Exception {
        Files.writeString(QuantifiedPaths.getConfigFile(), json);
        var config = MultithreadingConfig.loadOrCreateConfig(logger);
        assertParallelSettings();
        MultithreadingConfig.writePrettyJsonConfig(config);
        MultithreadingConfig.CONFIG = null;
        MultithreadingConfig.loadOrCreateConfig(logger);
        assertParallelSettings();
    }

    @Test
    void oldConfigsKeepParallelDefaults() throws Exception {
        Files.writeString(QuantifiedPaths.getConfigFile(), "{\"general\":{\"logToConsole\":false}}");
        var config = MultithreadingConfig.loadOrCreateConfig(logger);
        var defaults = new MultithreadingConfig.Config();
        assertThat(config.parallelMaxThreads).isEqualTo(defaults.parallelMaxThreads);
        assertThat(config.parallelQueueLimit).isEqualTo(defaults.parallelQueueLimit);
        assertThat(config.parallelMaxSlicesPerMod).isEqualTo(defaults.parallelMaxSlicesPerMod);
        assertThat(ParallelConfig.defaultFailurePolicy()).isEqualTo(ParallelFailurePolicy.FAIL_FAST);
        assertThat(config.logToConsole).isFalse();
    }

    private void assertParallelSettings() {
        assertThat(ParallelConfig.maxThreads()).isEqualTo(2);
        assertThat(ParallelConfig.queueLimit()).isEqualTo(1024);
        assertThat(ParallelConfig.maxSlicesPerMod()).isEqualTo(7);
        assertThat(ParallelConfig.defaultFailurePolicy()).isEqualTo(ParallelFailurePolicy.BEST_EFFORT);
    }
}
