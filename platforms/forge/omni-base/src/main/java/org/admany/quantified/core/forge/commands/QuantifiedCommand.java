package org.admany.quantified.core.forge.commands;

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
import net.minecraft.server.level.ServerPlayer;
import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.api.compute.GpuBackendType;
import org.admany.quantified.core.common.commands.QuantifiedCommandService;
import org.admany.quantified.core.forge.commands.ForgeCommandDevAccess;
import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.dev.DeveloperFeatures;
import org.admany.quantified.core.common.gpu.backend.GpuBackendRouter;
import org.admany.quantified.core.common.gpu.backend.VulkanExecutionSupport;
import org.admany.quantified.core.common.gpu.backend.VulkanProbeScheduler;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.core.OpenCLRuntime;
import org.admany.quantified.core.common.opencl.gpu.HardwareDetector;
import org.admany.quantified.core.common.telemetry.Metrics;
import org.admany.quantified.core.common.telemetry.TelemetryService;
import org.admany.quantified.core.common.gpu.backend.VulkanRuntime;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;

public final class QuantifiedCommand {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(QuantifiedCommand.class);
    private static final int COLOR_PREFIX = 0xAA00AA;
    private static final int COLOR_PRIMARY = 0xDA70D6;
    private static final int COLOR_SUCCESS = 0x69F0AE;
    private static final int COLOR_WARN = 0xFFC857;
    private static final int COLOR_ERROR = 0xFF6F61;

    private QuantifiedCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        try {
            dispatcher.register(literal("quantified")
                .then(literal("webpanel")
                    .executes(QuantifiedCommand::webPanel)
                    .then(argument("state", StringArgumentType.word())
                        .executes(ctx -> webPanel(ctx, StringArgumentType.getString(ctx, "state")))))
                .then(literal("stats").executes(QuantifiedCommand::stats))
                .then(literal("config").then(literal("reload").executes(QuantifiedCommand::reloadConfig)))
                .then(literal("mods").executes(QuantifiedCommand::mods))
                .then(literal("gpu").executes(QuantifiedCommand::gpuStatus))
                .then(literal("dev")
                    .then(literal("spotlight")
                        .executes(ctx -> toggleDeveloper(ctx, DeveloperToggle.SPOTLIGHT, ToggleAction.TOGGLE))
                        .then(argument("state", StringArgumentType.word())
                            .executes(ctx -> toggleDeveloper(ctx, DeveloperToggle.SPOTLIGHT, ToggleAction.EXPLICIT))))
                    .then(literal("status").executes(QuantifiedCommand::developerStatus))
                    .then(literal("opencl-probe").executes(QuantifiedCommand::developerOpenCLProbe))
                    .then(literal("vulkan-probe").executes(QuantifiedCommand::developerVulkanProbe)))
            );
            LOGGER.info("Quantified command tree registered for Forge omni bootstrap");
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
        MutableComponent prefix = textComponent("[Quantified] ");
        Style style = emptyStyle();
        style = styleWithColor(style, COLOR_PREFIX);
        style = styleWithBold(style, true);
        applyStyle(prefix, style);
        return append(prefix, content);
    }

    private static MutableComponent colored(String text, int color) {
        MutableComponent component = textComponent(text);
        applyStyle(component, styleWithColor(emptyStyle(), color));
        return component;
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
        MutableComponent component = textComponent(url);
        Style style = emptyStyle();
        style = styleWithColor(style, COLOR_PRIMARY);
        style = styleWithUnderlined(style, true);
        style = styleWithClickEvent(style, openUrlClickEvent(url));
        applyStyle(component, style);
        return component;
    }

    private static ClickEvent openUrlClickEvent(String url) {
        URI uri = URI.create(url);
        try {
            Class<?> openUrlClass = Class.forName("net.minecraft.network.chat.ClickEvent$OpenUrl");
            Constructor<?> constructor = openUrlClass.getConstructor(URI.class);
            return (ClickEvent) constructor.newInstance(uri);
        } catch (ClassNotFoundException ignored) {
        } catch (ReflectiveOperationException e) {
            LOGGER.debug("ClickEvent.OpenUrl unavailable; trying legacy ClickEvent constructor", e);
        }
        try {
            Class<?> actionClass = Class.forName("net.minecraft.network.chat.ClickEvent$Action");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object openUrlAction = Enum.valueOf((Class<Enum>) actionClass, "OPEN_URL");
            Constructor<?> constructor = ClickEvent.class.getConstructor(actionClass, String.class);
            return (ClickEvent) constructor.newInstance(openUrlAction, url);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to create open-url ClickEvent for " + url, e);
        }
    }

    private static int webPanel(CommandContext<CommandSourceStack> context) {
        return webPanel(context, "on");
    }

    private static int webPanel(CommandContext<CommandSourceStack> context, String rawState) {
        LOGGER.info("Executing /quantified webpanel with state={}", rawState);
        try {
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
                LOGGER.info("Forge omni /quantified webpanel result: success={}, message='{}', url='{}'",
                    result.success(), result.message(), result.url());
                if (!result.success()) {
                    sendError(context.getSource(), result.message());
                    return 0;
                }
                MutableComponent message = colored(result.message(), COLOR_SUCCESS);
                appendInPlace(message, textComponent(" | Open UI: "));
                appendInPlace(message, link(result.url()));
                deliver(context.getSource(), prefixed(message), false);
            } else {
                QuantifiedCommandService.DashboardResult result = QuantifiedCommandService.disableDashboard();
                sendInfo(context.getSource(), result.message());
            }
            return 1;
        } catch (Throwable t) {
            LOGGER.error("Forge omni /quantified webpanel handler crashed", t);
            try {
                sendError(context.getSource(), "Command failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable deliveryFailure) {
                LOGGER.error("Forge omni /quantified webpanel failed to deliver crash message", deliveryFailure);
            }
            return 0;
        }
    }

    private static void deliver(CommandSourceStack source, MutableComponent message, boolean failure) {
        try {
            sendComponent(source, message, failure);
            LOGGER.info("Quantified command feedback sent: failure={}, text='{}'", failure, message.getString());
            return;
        } catch (Throwable primaryFailure) {
            LOGGER.warn("Primary Quantified command feedback delivery failed; trying direct player delivery", primaryFailure);
        }
        try {
            ServerPlayer player = getPlayer(source);
            if (player != null) {
                sendComponent(player, message, failure);
                LOGGER.info("Quantified command feedback sent directly to player: failure={}, text='{}'", failure, message.getString());
                return;
            }
        } catch (Throwable playerFailure) {
            LOGGER.warn("Direct player Quantified command feedback delivery failed", playerFailure);
        }
        LOGGER.error("Unable to deliver Quantified command feedback: failure={}, text='{}'", failure, message.getString());
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
        LOGGER.info("Executing /quantified gpu");
        OpenCLManager.RuntimeStatus openclStatus = OpenCLManager.runtimeStatus();
        OpenCLRuntime.ProbeSnapshot openclProbeSnapshot = OpenCLRuntime.probeSnapshot();
        boolean vulkanBindingsPresent = VulkanRuntime.hasBindings();
        VulkanRuntime.RuntimeMode vulkanRuntimeMode = VulkanRuntime.runtimeMode();
        boolean vulkanProbeRuntimePresent = VulkanRuntime.hasProbeRuntime();
        VulkanRuntime.AvailabilitySnapshot vulkanSnapshot = VulkanRuntime.snapshot();
        boolean vulkanHardwareDetected = !vulkanSnapshot.devices().isEmpty();
        if (vulkanProbeRuntimePresent && !vulkanHardwareDetected && !VulkanExecutionSupport.isProbeRunning()) {
            VulkanProbeScheduler.triggerProbe("command:gpu-status");
        }
        boolean vulkanRuntimeInitialized = VulkanExecutionSupport.hasExecutableRuntime();
        String vulkanFailureReason = VulkanExecutionSupport.failureReason();
        if ((vulkanFailureReason == null || vulkanFailureReason.isBlank()) && vulkanRuntimeMode == VulkanRuntime.RuntimeMode.ISOLATED) {
            vulkanFailureReason = "Using isolated bundled Vulkan runtime for this Minecraft version";
        }
        boolean vulkanProbeAvailable = VulkanRuntime.isAvailable() || vulkanRuntimeMode == VulkanRuntime.RuntimeMode.ISOLATED;
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
            sendInfo(context.getSource(), "Vulkan runtime: " + (vulkanBindingsPresent ? "Native" : "Isolated bundled") + " | " + (vulkanRuntimeInitialized ? "Ready" : "Deferred until first use"));
        } else if (vulkanHardwareDetected) {
            VulkanRuntime.ProbeDeviceInfo device = vulkanSnapshot.devices().get(0);
            sendWarn(context.getSource(), "Vulkan: Hardware detected, runtime execution unavailable");
            sendInfo(context.getSource(), "Vulkan device: " + device.name());
            sendWarn(context.getSource(), "Vulkan reason: " + fallbackReason(vulkanFailureReason));
        } else if (vulkanProbeRuntimePresent) {
            sendWarn(context.getSource(), "Vulkan: Probe queued");
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
                OpenCLRuntime.ProbeSnapshot probeSnapshot = OpenCLRuntime.probeSnapshot();
                if (!probeSnapshot.devices().isEmpty()) {
                    OpenCLRuntime.ProbeDeviceInfo device = probeSnapshot.devices().get(0);
                    sendWarn(context.getSource(), "OpenCL hardware was detected (" + device.name()
                        + "), but runtime execution is unavailable: " + reason);
                } else {
                    sendWarn(context.getSource(), "OpenCL acceleration is not available on this system: " + reason);
                }
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
        if (!ForgeCommandDevAccess.hasDevAccess(context.getSource())) {
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

    private static void applyStyle(MutableComponent component, Style style) {
        try {
            Method preferred = findMethod(component.getClass(), "setStyle", Style.class);
            if (preferred == null) {
                preferred = findMethod(component.getClass(), "withStyle", Style.class);
            }
            if (preferred == null) {
                preferred = findMethod(component.getClass(), "m_6270_", Style.class);
            }
            if (preferred == null) {
                preferred = findMethod(component.getClass(), "m_130948_", Style.class);
            }
            if (preferred != null && MutableComponent.class.isAssignableFrom(preferred.getReturnType())) {
                preferred.invoke(component, style);
                return;
            }
            for (Method method : component.getClass().getMethods()) {
                if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != Style.class) {
                    continue;
                }
                if (!MutableComponent.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                method.invoke(component, style);
                return;
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to apply style to Quantified command component", t);
        }
    }

    private static Style emptyStyle() {
        try {
            for (java.lang.reflect.Field field : Style.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != Style.class) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof Style style) {
                    return style;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to resolve empty chat style reflectively", t);
        }
        throw new IllegalStateException("Unable to resolve base chat style");
    }

    private static Style styleWithColor(Style base, int color) {
        try {
            Method preferred = findMethod(base.getClass(), "withColor", int.class);
            if (preferred == null) {
                preferred = findMethod(base.getClass(), "m_178520_", int.class);
            }
            if (preferred != null && preferred.getReturnType() == Style.class) {
                Object result = preferred.invoke(base, color);
                if (result instanceof Style style) {
                    return style;
                }
            }
            for (Method method : base.getClass().getMethods()) {
                if (method.getParameterCount() != 1 || method.getReturnType() != Style.class) {
                    continue;
                }
                Class<?> param = method.getParameterTypes()[0];
                if (param == int.class || param == Integer.class) {
                    Object result = method.invoke(base, color);
                    if (result instanceof Style style) {
                        return style;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to apply chat color reflectively", t);
        }
        return base;
    }

    private static Style styleWithBold(Style base, boolean value) {
        return styleWithBoolean(base, value, "bold");
    }

    private static Style styleWithUnderlined(Style base, boolean value) {
        return styleWithBoolean(base, value, "under");
    }

    private static Style styleWithBoolean(Style base, boolean value, String nameHint) {
        try {
            String preferredName = switch (nameHint) {
                case "bold" -> "m_131136_";
                case "under" -> "m_131162_";
                default -> "";
            };
            Method preferred = preferredName.isEmpty() ? null : findMethod(base.getClass(), preferredName, Boolean.class);
            if (preferred != null && preferred.getReturnType() == Style.class) {
                Object result = preferred.invoke(base, Boolean.valueOf(value));
                if (result instanceof Style style) {
                    return style;
                }
            }
            for (Method method : base.getClass().getMethods()) {
                if (method.getParameterCount() != 1
                    || (method.getParameterTypes()[0] != Boolean.TYPE && method.getParameterTypes()[0] != Boolean.class)
                    || method.getReturnType() != Style.class) {
                    continue;
                }
                String name = method.getName().toLowerCase();
                if (!name.contains(nameHint)) {
                    continue;
                }
                Object result = method.invoke(base, value);
                if (result instanceof Style style) {
                    return style;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to apply boolean style '{}' reflectively", nameHint, t);
        }
        return base;
    }

    private static Style styleWithClickEvent(Style base, ClickEvent event) {
        try {
            Method preferred = findMethod(base.getClass(), "withClickEvent", ClickEvent.class);
            if (preferred == null) {
                preferred = findMethod(base.getClass(), "m_131142_", ClickEvent.class);
            }
            if (preferred != null && preferred.getReturnType() == Style.class) {
                Object result = preferred.invoke(base, event);
                if (result instanceof Style style) {
                    return style;
                }
            }
            for (Method method : base.getClass().getMethods()) {
                if (method.getParameterCount() != 1 || method.getReturnType() != Style.class) {
                    continue;
                }
                if (!ClickEvent.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    continue;
                }
                Object result = method.invoke(base, event);
                if (result instanceof Style style) {
                    return style;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to apply click event reflectively", t);
        }
        return base;
    }

    private static MutableComponent textComponent(String text) {
        try {
            Method preferred = findMethod(Component.class, "literal", String.class);
            if (preferred == null) {
                preferred = findMethod(Component.class, "m_237113_", String.class);
            }
            if (preferred != null && Modifier.isStatic(preferred.getModifiers())
                && MutableComponent.class.isAssignableFrom(preferred.getReturnType())) {
                Object result = preferred.invoke(null, text);
                if (result instanceof MutableComponent mutable) {
                    return mutable;
                }
            }
            for (Method method : Component.class.getMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != String.class) {
                    continue;
                }
                if (!MutableComponent.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                Object result = method.invoke(null, text);
                if (result instanceof MutableComponent mutable) {
                    return mutable;
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to create literal component", t);
        }
        throw new IllegalStateException("Unable to create literal component");
    }

    private static MutableComponent append(MutableComponent left, Component right) {
        appendInPlace(left, right);
        return left;
    }

    private static void appendInPlace(MutableComponent target, Component extra) {
        try {
            Method preferred = findMethod(target.getClass(), "append", Component.class);
            if (preferred == null) {
                preferred = findMethod(target.getClass(), "m_7220_", Component.class);
            }
            if (preferred != null && MutableComponent.class.isAssignableFrom(preferred.getReturnType())) {
                preferred.invoke(target, extra);
                return;
            }
            for (Method method : target.getClass().getMethods()) {
                if (method.getParameterCount() != 1 || !Component.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    continue;
                }
                if (!MutableComponent.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                method.invoke(target, extra);
                return;
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to append component text", t);
        }
        throw new IllegalStateException("Unable to append component text");
    }

    private static ServerPlayer getPlayer(CommandSourceStack source) {
        try {
            for (Method method : source.getClass().getMethods()) {
                if (method.getParameterCount() == 0 && ServerPlayer.class.isAssignableFrom(method.getReturnType())) {
                    Object result = method.invoke(source);
                    if (result instanceof ServerPlayer player) {
                        return player;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void sendComponent(Object target, Component message, boolean failure) {
        if (target instanceof CommandSourceStack source) {
            if (failure && invokeNamedComponentMethod(source, message, "sendFailure", "m_81352_")) {
                return;
            }
            if (invokeNamedComponentMethod(source, message, "sendSystemMessage", "m_243053_")) {
                return;
            }
            if (invokeSupplierMessage(source, message)) {
                return;
            }
        }
        if (target instanceof ServerPlayer player) {
            if (invokeNamedComponentMethod(player, message, "sendSystemMessage", "m_213846_")) {
                return;
            }
        }
        for (Method method : target.getClass().getMethods()) {
            if (method.getParameterCount() != 1) {
                continue;
            }
            if (!Component.class.isAssignableFrom(method.getParameterTypes()[0])) {
                continue;
            }
            if (method.getReturnType() != void.class) {
                continue;
            }
            try {
                method.invoke(target, message);
                return;
            } catch (Throwable ignored) {
            }
        }
        throw new IllegalStateException("No compatible component delivery method found on " + target.getClass().getName());
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static boolean invokeNamedComponentMethod(Object target, Component message, String... names) {
        for (String name : names) {
            Method method = findMethod(target.getClass(), name, Component.class);
            if (method == null || method.getReturnType() != void.class) {
                continue;
            }
            try {
                method.invoke(target, message);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean invokeSupplierMessage(CommandSourceStack source, Component message) {
        for (String name : new String[]{"sendSuccess", "m_288197_"}) {
            Method method = findMethod(source.getClass(), name, java.util.function.Supplier.class, boolean.class);
            if (method == null || method.getReturnType() != void.class) {
                continue;
            }
            try {
                method.invoke(source, (java.util.function.Supplier<Component>) () -> message, false);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
