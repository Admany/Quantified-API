package org.admany.quantified.core.neoforge.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.permissions.Permissions;
import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.api.compute.GpuBackendType;
import org.admany.quantified.core.common.commands.QuantifiedCommandService;
import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.dev.DeveloperFeatures;
import org.admany.quantified.core.common.gpu.backend.GpuBackendRouter;
import org.admany.quantified.core.common.gpu.backend.VulkanExecutionSupport;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.core.OpenCLRuntime;
import org.admany.quantified.core.common.opencl.gpu.HardwareDetector;
import org.admany.quantified.core.common.telemetry.Metrics;
import org.admany.quantified.core.common.telemetry.TelemetryService;
import org.admany.quantified.core.common.gpu.backend.VulkanRuntime;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;

import java.net.URI;

public final class QuantifiedNeoForgeCommand {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(QuantifiedNeoForgeCommand.class);
    private static final int COLOR_PREFIX = 0xAA00AA;
    private static final int COLOR_PRIMARY = 0xDA70D6;
    private static final int COLOR_SUCCESS = 0x69F0AE;
    private static final int COLOR_WARN = 0xFFC857;
    private static final int COLOR_ERROR = 0xFF6F61;

    private QuantifiedNeoForgeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        try {
            dispatcher.register(literal("quantified")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                .then(literal("webpanel")
                    .executes(QuantifiedNeoForgeCommand::webPanel)
                    .then(argument("state", StringArgumentType.word())
                        .executes(ctx -> webPanel(ctx, StringArgumentType.getString(ctx, "state")))))
                .then(literal("stats").executes(QuantifiedNeoForgeCommand::stats))
                .then(literal("config").then(literal("reload").executes(QuantifiedNeoForgeCommand::reloadConfig)))
                .then(literal("mods").executes(QuantifiedNeoForgeCommand::mods))
                .then(literal("gpu").executes(QuantifiedNeoForgeCommand::gpuStatus))
                .then(literal("dev")
                    .then(literal("spotlight")
                        .executes(ctx -> toggleDeveloper(ctx, DeveloperToggle.SPOTLIGHT, ToggleAction.TOGGLE))
                        .then(argument("state", StringArgumentType.word())
                            .executes(ctx -> toggleDeveloper(ctx, DeveloperToggle.SPOTLIGHT, ToggleAction.EXPLICIT))))
                    .then(literal("status").executes(QuantifiedNeoForgeCommand::developerStatus))
                    .then(literal("opencl-probe").executes(QuantifiedNeoForgeCommand::developerOpenCLProbe))
                    .then(literal("vulkan-probe").executes(QuantifiedNeoForgeCommand::developerVulkanProbe)))
            );
        } catch (Throwable t) {
            LOGGER.error("Failed to register Quantified commands - skipping registration to avoid game crash", t);
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(
        String name,
        com.mojang.brigadier.arguments.ArgumentType<T> type
    ) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    private static MutableComponent prefixed(MutableComponent content) {
        return Component.literal("[Quantified] ").withStyle(style -> style.withColor(COLOR_PREFIX).withBold(true)).append(content);
    }

    private static MutableComponent colored(String text, int color) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(color));
    }

    private static void sendInfo(CommandSourceStack source, String message) {
        deliver(source, prefixed(colored(message, COLOR_PRIMARY)), false);
    }

    private static void sendSuccess(CommandSourceStack source, String message) {
        deliver(source, prefixed(colored(message, COLOR_SUCCESS)), false);
    }

    private static void sendWarn(CommandSourceStack source, String message) {
        deliver(source, prefixed(colored(message, COLOR_WARN)), false);
    }

    private static void sendError(CommandSourceStack source, String message) {
        deliver(source, prefixed(colored(message, COLOR_ERROR)), true);
    }

    private static MutableComponent link(String url) {
        Style style = Style.EMPTY
            .withColor(COLOR_PRIMARY)
            .withUnderlined(true)
            .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)));
        return Component.literal(url).setStyle(style);
    }

    private static int webPanel(CommandContext<CommandSourceStack> context) {
        return webPanel(context, "on");
    }

    private static int webPanel(CommandContext<CommandSourceStack> context, String rawState) {
        LOGGER.info("Executing /quantified webpanel with state={}", rawState);
        if (!requireAdmin(context)) {
            return 0;
        }
        boolean enable;
        try {
            enable = parseToggle(rawState);
        } catch (IllegalArgumentException ex) {
            sendError(context.getSource(), ex.getMessage());
            return 0;
        }

        if (enable) {
            QuantifiedCommandService.DashboardResult result = QuantifiedCommandService.enableDashboardForCommand();
            if (!result.success()) {
                sendError(context.getSource(), result.message());
                return 0;
            }
            MutableComponent message = colored(result.message(), COLOR_SUCCESS);
            message.append(Component.literal(" | "));
            message.append(colored("Open UI: ", COLOR_PRIMARY));
            message.append(link(result.url()));
            deliver(context.getSource(), prefixed(message), false);
        } else {
            QuantifiedCommandService.DashboardResult result = QuantifiedCommandService.disableDashboard();
            sendInfo(context.getSource(), result.message());
        }
        return 1;
    }

    private static void deliver(CommandSourceStack source, MutableComponent message, boolean failure) {
        try {
            if (failure) {
                source.sendFailure(message);
            } else {
                source.sendSuccess(() -> message, false);
            }
        } catch (Throwable primaryFailure) {
            source.sendSystemMessage(message);
        }
    }

    private static int stats(CommandContext<CommandSourceStack> context) {
        sendInfo(context.getSource(), "Performance Statistics");

        TelemetryService.SchedulerSnapshot snap = TelemetryService.getLatest();
        if (snap != null) {
            sendInfo(context.getSource(), String.format("Tasks: %d submitted, %d executed (%.1f/s)",
                snap.submitted, snap.executed, snap.execRate));
            sendInfo(context.getSource(), String.format("Queue: %d | Workers: %d foreground, %d background",
                snap.queueSize, snap.foregroundWorkers, snap.backgroundWorkers));
        } else {
            sendWarn(context.getSource(), "No telemetry data available");
        }

        long requests = Metrics.get("async_requests");
        long cacheHits = Metrics.get("cache_hits");
        long cacheMisses = Metrics.get("cache_misses");
        long networkPackets = Metrics.get("network_packets");

        sendInfo(context.getSource(), String.format("Requests: %d | Cache: %d/%d hits (%.1f%%)",
            requests, cacheHits, cacheHits + cacheMisses,
            cacheHits + cacheMisses > 0 ? (cacheHits * 100.0 / (cacheHits + cacheMisses)) : 0.0));
        sendInfo(context.getSource(), String.format("Network: %d packets", networkPackets));
        return 1;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        MultithreadingConfig.initializeGlobals(MultithreadingConfig.LOGGER);
        sendSuccess(context.getSource(), "Configuration reloaded successfully");
        return 1;
    }

    private static int mods(CommandContext<CommandSourceStack> context) {
        var registeredMods = QuantifiedCoreRuntime.getRegisteredMods();
        sendInfo(context.getSource(), "Registered Mods");

        if (registeredMods.isEmpty()) {
            sendWarn(context.getSource(), "No mods registered with Quantified API");
            return 1;
        }

        sendInfo(context.getSource(), String.format("Total: %d mods", registeredMods.size()));
        for (var entry : registeredMods.entrySet()) {
            QuantifiedCoreRuntime.ModInfo info = entry.getValue();
            String status = info.active ? "Active" : "Inactive";
            long age = System.currentTimeMillis() - info.lastActivity;
            String ageStr = age < 60000 ? (age / 1000) + "s ago" : (age / 60000) + "m ago";
            sendInfo(context.getSource(), info.modId + " v" + info.version + " | " + status + " | Last: " + ageStr);
        }
        return 1;
    }

    private static int gpuStatus(CommandContext<CommandSourceStack> context) {
        OpenCLManager.RuntimeStatus openclStatus = OpenCLManager.runtimeStatus();
        OpenCLRuntime.ProbeSnapshot openclProbeSnapshot = OpenCLRuntime.probeSnapshot();
        boolean vulkanBindingsPresent = VulkanRuntime.hasBindings();
        boolean vulkanProbeRuntimePresent = VulkanRuntime.hasProbeRuntime();
        VulkanRuntime.AvailabilitySnapshot vulkanSnapshot = VulkanRuntime.snapshot();
        boolean vulkanHardwareDetected = !vulkanSnapshot.devices().isEmpty();
        boolean vulkanRuntimeInitialized = VulkanExecutionSupport.hasExecutableRuntime();
        String vulkanFailureReason = vulkanBindingsPresent
            ? VulkanExecutionSupport.failureReason()
            : "Using isolated bundled Vulkan runtime for this Minecraft version";
        boolean vulkanProbeAvailable = VulkanRuntime.isAvailable() || VulkanRuntime.runtimeMode() == VulkanRuntime.RuntimeMode.ISOLATED;
        GpuBackendType activeBackend = GpuBackendRouter.selectBackend(
            "dashboard",
            null,
            true,
            OpenCLManager.hasExecutableRuntime(),
            true,
            VulkanExecutionSupport.hasExecutableRuntime()
        ).backendType();
        GpuBackendPreference preferredBackend = GpuBackendRouter.getDefaultPreference();

        sendInfo(context.getSource(), "GPU Acceleration Status");
        sendInfo(context.getSource(), "Preferred: " + preferredBackend.displayLabel() + " | Active: " + backendLabel(activeBackend));

        if (vulkanProbeAvailable) {
            sendSuccess(context.getSource(), "Vulkan: Probe Succeeded");
            sendInfo(context.getSource(), "Vulkan device: " + VulkanExecutionSupport.deviceName());
            sendInfo(context.getSource(), "Vulkan runtime: " + (vulkanRuntimeInitialized ? "Initialized" : "Deferred until first use"));
        } else if (vulkanHardwareDetected) {
            VulkanRuntime.ProbeDeviceInfo device = vulkanSnapshot.devices().get(0);
            sendWarn(context.getSource(), "Vulkan: Hardware detected, runtime execution unavailable");
            sendInfo(context.getSource(), "Vulkan device: " + device.name());
            sendWarn(context.getSource(), "Vulkan reason: " + fallbackReason(vulkanFailureReason));
        } else if (vulkanProbeRuntimePresent) {
            sendWarn(context.getSource(), "Vulkan: Probe runtime bundled, no compatible device detected");
            sendWarn(context.getSource(), "Vulkan reason: " + fallbackReason(vulkanFailureReason));
        } else {
            sendWarn(context.getSource(), "Vulkan: Not Available");
            sendWarn(context.getSource(), "Vulkan reason: " + fallbackReason(vulkanFailureReason));
        }

        if (OpenCLManager.isAvailable()) {
            sendSuccess(context.getSource(), "OpenCL: Available");
            var status = OpenCLManager.getGPUStatus();
            if (status != null) {
                long cacheUsedMb = Math.max(0L, status.usedVramBytes()) / (1024 * 1024);
                long cacheBudgetMb = Math.max(1L, status.totalVramBytes()) / (1024 * 1024);
                double vramUsage = status.memoryUtilization() * 100.0;
                double systemUsage = status.systemUsageRatio() * 100.0;
                double computeUsage = status.computeUtilization() * 100.0;
                double temperature = status.temperatureC();
                sendInfo(context.getSource(), String.format(
                    "OpenCL cache: %d/%d MB (%.1f%%) | System VRAM: %.1f%% | Compute: %.1f%% | Temp: %.1fC",
                    cacheUsedMb, cacheBudgetMb, vramUsage, systemUsage, computeUsage, temperature));
            } else {
                sendWarn(context.getSource(), "OpenCL monitoring: Unavailable");
            }
        } else if (!openclProbeSnapshot.devices().isEmpty()) {
            OpenCLRuntime.ProbeDeviceInfo device = openclProbeSnapshot.devices().get(0);
            sendWarn(context.getSource(), "OpenCL: Hardware detected, runtime execution unavailable");
            sendInfo(context.getSource(), "OpenCL device: " + device.name() + " | Vendor: " + device.vendor()
                + " | Type: " + device.type());
            sendWarn(context.getSource(), "OpenCL reason: " + fallbackReason(openclStatus.failureReason()));
        } else {
            sendWarn(context.getSource(), "OpenCL: Not Available");
            sendWarn(context.getSource(), "OpenCL reason: " + fallbackReason(openclStatus.failureReason()));

            HardwareDetector.HardwareStatus hwStatus = HardwareDetector.detailedDetect();
            sendInfo(context.getSource(), String.format("OpenCL confidence: %.0f%% | GPU confidence: %.0f%%",
                hwStatus.getOpenCLConfidence() * 100, hwStatus.getGPUConfidence() * 100));
            boolean contextOk = hwStatus.getDetectionResults().isContextCreationSuccessful();
            sendInfo(context.getSource(), "OpenCL context test: " + (contextOk ? "PASS" : "FAIL"));

            String reason = fallbackReason(openclStatus.failureReason()).toLowerCase();
            if (reason.contains("binding") || reason.contains("no java opencl")) {
                sendInfo(context.getSource(), "Tip: Include the LWJGL OpenCL binding in the mod classpath or run without the sandboxing launcher.");
            }
        }

        return 1;
    }

    private static int toggleDeveloper(CommandContext<CommandSourceStack> context, DeveloperToggle toggle, ToggleAction action) {
        if (!requireAdmin(context)) {
            return 0;
        }
        boolean enable;
        if (action == ToggleAction.TOGGLE) {
            enable = !currentDeveloperState(toggle);
        } else {
            try {
                enable = parseToggle(StringArgumentType.getString(context, "state"));
            } catch (IllegalArgumentException ex) {
                sendError(context.getSource(), ex.getMessage());
                return 0;
            }
        }
        if (toggle == DeveloperToggle.SPOTLIGHT) {
            DeveloperFeatures.setModSpotlightEnabled(enable, true);
        }
        sendInfo(context.getSource(), "Developer " + toggle.displayName + " " + (enable ? "enabled" : "disabled"));
        return 1;
    }

    private static String backendLabel(GpuBackendType backendType) {
        return switch (backendType) {
            case CPU -> "CPU";
            case OPENCL -> "OpenCL";
            case VULKAN -> "Vulkan";
        };
    }

    private static int developerStatus(CommandContext<CommandSourceStack> context) {
        if (!requireAdmin(context)) {
            return 0;
        }

        sendInfo(context.getSource(), "Developer Features");
        sendInfo(context.getSource(), "Mode: " + flag(DeveloperFeatures.isDeveloperModeEnabled()));
        sendInfo(context.getSource(), "Hints: ALWAYS ON | Spotlight: " + flag(DeveloperFeatures.isModSpotlightEnabled()));
        return 1;
    }

    private static int developerOpenCLProbe(CommandContext<CommandSourceStack> context) {
        if (!requireAdmin(context)) {
            return 0;
        }
        if (!DeveloperFeatures.isDeveloperModeEnabled()) {
            sendError(context.getSource(), "Developer mode is not enabled");
            return 0;
        }

        sendInfo(context.getSource(), "Starting OpenCL probe in background...");
        DeveloperFeatures.runOpenCLProbe().whenComplete((success, ex) -> {
            if (ex != null) {
                sendError(context.getSource(), "OpenCL probe failed: " + ex.getMessage());
                return;
            }
            if (Boolean.TRUE.equals(success)) {
                sendSuccess(context.getSource(), "OpenCL acceleration initialized successfully");
            } else {
                String reason = fallbackReason(OpenCLManager.runtimeStatus().failureReason());
                sendWarn(context.getSource(), "OpenCL acceleration is not available on this system: " + reason);
            }
        });
        return 1;
    }

    private static int developerVulkanProbe(CommandContext<CommandSourceStack> context) {
        if (!requireAdmin(context)) {
            return 0;
        }
        if (!DeveloperFeatures.isDeveloperModeEnabled()) {
            sendError(context.getSource(), "Developer mode is not enabled");
            return 0;
        }

        sendInfo(context.getSource(), "Starting Vulkan probe in background...");
        DeveloperFeatures.runVulkanProbe().whenComplete((success, ex) -> {
            if (ex != null) {
                sendError(context.getSource(), "Vulkan probe failed: " + ex.getMessage());
                return;
            }
            if (Boolean.TRUE.equals(success)) {
                sendSuccess(context.getSource(), "Vulkan probe succeeded on " + VulkanExecutionSupport.deviceName());
                sendInfo(context.getSource(), "Vulkan runtime will initialize on first use");
            } else {
                String reason = fallbackReason(VulkanExecutionSupport.failureReason());
                sendWarn(context.getSource(), "Vulkan acceleration is not available on this system: " + reason);
            }
        });
        return 1;
    }

    private static boolean requireAdmin(CommandContext<CommandSourceStack> context) {
        if (!context.getSource().permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
            sendError(context.getSource(), "Developer features require operator permission (level 2+)");
            return false;
        }
        return true;
    }

    private static boolean parseToggle(String state) {
        return switch (state.toLowerCase()) {
            case "on", "true", "enable", "enabled" -> true;
            case "off", "false", "disable", "disabled" -> false;
            default -> throw new IllegalArgumentException("Unknown state: " + state);
        };
    }

    private static String flag(boolean value) {
        return value ? "ON" : "OFF";
    }

    private static String fallbackReason(String reason) {
        return reason != null && !reason.isBlank() ? reason : "Unknown reason";
    }

    private enum DeveloperToggle {
        SPOTLIGHT("mod spotlight");

        private final String displayName;

        DeveloperToggle(String displayName) {
            this.displayName = displayName;
        }
    }

    private enum ToggleAction {
        TOGGLE,
        EXPLICIT
    }

    private static boolean currentDeveloperState(DeveloperToggle toggle) {
        return toggle == DeveloperToggle.SPOTLIGHT && DeveloperFeatures.isModSpotlightEnabled();
    }
}
