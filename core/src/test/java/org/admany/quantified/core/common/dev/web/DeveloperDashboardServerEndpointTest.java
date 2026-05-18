package org.admany.quantified.core.common.dev.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.admany.quantified.api.QuantifiedAPI;
import org.admany.quantified.core.common.telemetry.TaskKindTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class DeveloperDashboardServerEndpointTest {

    private static final String MOD_ID = "dashboard_endpoint_test";

    @AfterEach
    void tearDown() {
        QuantifiedAPI.disconnect(MOD_ID);
    }

    @Test
    void modMetricsPayloadIncludesRegisteredModsAndRouteTelemetry() throws Exception {
        QuantifiedAPI.register(MOD_ID, "Dashboard Endpoint Test", "2.0.0");
        TaskKindTelemetry.recordMultithreading(MOD_ID, "plainWork");
        TaskKindTelemetry.recordParallel(MOD_ID, "parallelWork");
        TaskKindTelemetry.recordBatch(MOD_ID, "parallelWork", 4);
        TaskKindTelemetry.recordGpu(MOD_ID, "gpuWork");

        JsonObject payload = buildModMetricsPayload();

        assertThat(payload.get("generatedAt").getAsLong()).isGreaterThan(0L);
        assertThat(payload.get("windowMs").getAsLong()).isGreaterThan(0L);

        JsonObject summary = payload.getAsJsonObject("summary");
        assertThat(summary.get("modsTracked").getAsLong()).isGreaterThanOrEqualTo(1L);
        assertThat(summary.get("taskEvents").getAsLong()).isGreaterThanOrEqualTo(3L);
        assertThat(summary.get("gpuEvents").getAsLong()).isGreaterThanOrEqualTo(1L);

        JsonObject mod = findBy(payload.getAsJsonArray("mods"), "modId", MOD_ID);
        assertThat(mod).isNotNull();
        assertThat(mod.get("displayName").getAsString()).isEqualTo("Dashboard Endpoint Test");
        assertThat(mod.get("online").getAsBoolean()).isTrue();
        assertThat(mod.get("taskEvents").getAsLong()).isGreaterThanOrEqualTo(3L);
        assertThat(mod.get("gpuEvents").getAsLong()).isGreaterThanOrEqualTo(1L);
        assertThat(mod.get("parallelEvents").getAsLong()).isGreaterThanOrEqualTo(1L);
        assertThat(mod.get("multithreadingEvents").getAsLong()).isGreaterThanOrEqualTo(1L);
        assertThat(mod.get("batchCount").getAsLong()).isGreaterThanOrEqualTo(1L);
        assertThat(mod.get("batchMax").getAsInt()).isGreaterThanOrEqualTo(4);

        JsonObject gpuTask = findBy(payload.getAsJsonArray("tasks"), "taskName", "gpuWork");
        assertThat(gpuTask).isNotNull();
        assertThat(gpuTask.get("modId").getAsString()).isEqualTo(MOD_ID);
        assertThat(gpuTask.get("route").getAsString()).isEqualTo("GPU Accel");
    }

    private static JsonObject buildModMetricsPayload() throws Exception {
        Method method = DeveloperDashboardServer.class.getDeclaredMethod("buildModMetricsPayload");
        method.setAccessible(true);
        return (JsonObject) method.invoke(null);
    }

    private static JsonObject findBy(JsonArray array, String property, String value) {
        for (int i = 0; i < array.size(); i++) {
            JsonObject object = array.get(i).getAsJsonObject();
            if (value.equals(object.get(property).getAsString())) {
                return object;
            }
        }
        return null;
    }
}
