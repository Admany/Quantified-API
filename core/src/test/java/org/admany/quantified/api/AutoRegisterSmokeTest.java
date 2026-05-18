package org.admany.quantified.api;

import examplemods.fabricautoreg.FabricAutoRegisterCaller;
import net.fabricmc.loader.api.FabricLoader;
import org.admany.quantified.core.common.async.core.AsyncManager;
import org.admany.quantified.core.common.async.core.AsyncManagerBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class AutoRegisterSmokeTest {

    private static final String MOD_ID = "fabric_auto";
    private static ScheduledExecutorService testExecutor;

    @BeforeAll
    static void setUpAll() {
        testExecutor = Executors.newScheduledThreadPool(2);
        AsyncManagerBootstrap bootstrap = AsyncManagerBootstrap.defaults(Runtime.getRuntime().availableProcessors());
        AsyncManager.initialise(bootstrap, testExecutor);
    }

    @AfterEach
    void tearDownEach() {
        QuantifiedAPI.disconnect(MOD_ID);
        FabricLoader.clearTestMods();
    }

    @AfterAll
    static void tearDownAll() {
        QuantifiedAPI.disconnect(MOD_ID);
        FabricLoader.clearTestMods();
        AsyncManager.shutdown();
        if (testExecutor != null) {
            testExecutor.shutdownNow();
        }
    }

    @Test
    void fabricCallerAutoRegistersOnFirstComputeSubmission() throws Exception {
        FabricLoader.installTestMod(
            MOD_ID,
            "Fabric Auto Test",
            "9.8.7",
            codeSourceRoot(FabricAutoRegisterCaller.class)
        );

        String result = FabricAutoRegisterCaller.submitSimpleTask().join();

        assertThat(result).isEqualTo("ok");
        assertThat(handleMap()).containsKey(MOD_ID);
        assertThat(currentHandleModId()).isEqualTo(MOD_ID);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> handleMap() throws ReflectiveOperationException {
        Field field = QuantifiedAPI.class.getDeclaredField("handlesByMod");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(null);
    }

    private static String currentHandleModId() throws ReflectiveOperationException {
        Field currentHandleField = QuantifiedAPI.class.getDeclaredField("currentHandle");
        currentHandleField.setAccessible(true);
        ThreadLocal<?> threadLocal = (ThreadLocal<?>) currentHandleField.get(null);
        Object handle = threadLocal.get();
        assertThat(handle).isNotNull();

        Field modIdField = handle.getClass().getDeclaredField("modId");
        modIdField.setAccessible(true);
        return (String) modIdField.get(handle);
    }

    private static Path codeSourceRoot(Class<?> type) throws URISyntaxException {
        return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toAbsolutePath()
            .normalize();
    }
}
