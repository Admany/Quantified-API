package org.admany.quantified.core.fabric.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.api.compute.GpuBackendType;
import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.dev.DeveloperFeatures;
import org.admany.quantified.core.common.gpu.backend.GpuBackendRouter;
import org.admany.quantified.core.common.gpu.backend.VulkanRuntime;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.admany.quantified.core.common.telemetry.TelemetryService;
import org.admany.quantified.core.common.vulkan.core.VulkanManager;

public final class QuantifiedFabricCommand {

    private static final int COLOR_PREFIX = 0xAA00AA;
    private static final int COLOR_PRIMARY = 0xDA70D6;
    private static final int COLOR_SUCCESS = 0x69F0AE;
    private static final int COLOR_WARN = 0xFFC857;
    private static final int COLOR_ERROR = 0xFF6F61;

    private QuantifiedFabricCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("quantified")
            .then(Commands.literal("webpanel")
                .executes(ctx -> webPanel(ctx.getSource(), "on"))
                .then(Commands.argument("state", StringArgumentType.word())
                    .executes(ctx -> webPanel(ctx.getSource(), StringArgumentType.getString(ctx, "state")))))
            .then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource())))
            .then(Commands.literal("mods").executes(ctx -> mods(ctx.getSource())))
            .then(Commands.literal("gpu").executes(ctx -> gpuStatus(ctx.getSource()))));
    }

    private static int webPanel(CommandSourceStack source, String rawState) {
        if (!requireAdmin(source)) {
            return 0;
        }
        boolean enable;
        try {
            enable = parseToggle(rawState);
        } catch (IllegalArgumentException ex) {
            sendError(source, ex.getMessage());
            return 0;
        }

        if (enable) {
            boolean wasActive = DeveloperFeatures.isDeveloperModeEnabled();
            DeveloperFeatures.setDeveloperMode(true, true);
            DeveloperFeatures.setDashboardEnabled(true, true);
            int port = MultithreadingConfig.CONFIG.developerDashboardPort;
            String url = "http://127.0.0.1:" + port + "/";
            MutableComponent message = colored(wasActive ? "Developer dashboard refreshed" : "Developer dashboard enabled", COLOR_SUCCESS);
            message.append(Component.literal(" | "));
            message.append(colored("Open UI: ", COLOR_PRIMARY));
            message.append(link(url));
            source.sendSuccess(() -> prefixed(message), false);
        } else {
            DeveloperFeatures.setDashboardEnabled(false, true);
            DeveloperFeatures.setDeveloperMode(false, true);
            sendInfo(source, "Developer dashboard disabled");
        }
        return 1;
    }

    private static int stats(CommandSourceStack source) {
        sendInfo(source, "Performance Statistics");
        TelemetryService.SchedulerSnapshot snap = TelemetryService.getLatest();
        if (snap != null) {
            sendInfo(source, String.format("Tasks: %d submitted, %d executed (%.1f/s)",
                snap.submitted, snap.executed, snap.execRate));
            sendInfo(source, String.format("Queue: %d | Workers: %d foreground, %d background",
                snap.queueSize, snap.foregroundWorkers, snap.backgroundWorkers));
        } else {
            sendWarn(source, "No telemetry data available");
        }
        return 1;
    }

    private static int mods(CommandSourceStack source) {
        var registeredMods = QuantifiedCoreRuntime.getRegisteredMods();
        sendInfo(source, "Registered Mods");
        if (registeredMods.isEmpty()) {
            sendWarn(source, "No mods registered with Quantified API");
            return 1;
        }
        sendInfo(source, String.format("Total: %d mods", registeredMods.size()));
        for (var entry : registeredMods.entrySet()) {
            QuantifiedCoreRuntime.ModInfo info = entry.getValue();
            String status = info.active ? "Active" : "Inactive";
            long age = System.currentTimeMillis() - info.lastActivity;
            String ageStr = age < 60000 ? (age / 1000) + "s ago" : (age / 60000) + "m ago";
            sendInfo(source, info.modId + " v" + info.version + " | " + status + " | Last: " + ageStr);
        }
        return 1;
    }

    private static int gpuStatus(CommandSourceStack source) {
        boolean vulkanBindingsPresent = VulkanRuntime.hasBindings();
        boolean vulkanRuntimeInitialized = vulkanBindingsPresent && VulkanManager.runtimeStatus().isAvailable();
        boolean vulkanProbeAvailable = vulkanBindingsPresent && VulkanRuntime.isAvailable();
        GpuBackendType activeBackend = GpuBackendRouter.selectBackend("dashboard", null, true, true).backendType();
        GpuBackendPreference preferredBackend = GpuBackendRouter.getDefaultPreference();

        sendInfo(source, "GPU Acceleration Status");
        sendInfo(source, "Preferred: " + preferredBackend.displayLabel() + " | Active: " + backendLabel(activeBackend));

        if (vulkanProbeAvailable) {
            sendSuccess(source, "Vulkan: Probe Succeeded");
            sendInfo(source, "Vulkan device: " + VulkanManager.deviceName());
            sendInfo(source, "Vulkan runtime: " + (vulkanRuntimeInitialized ? "Initialized" : "Deferred until first use"));
        } else {
            sendWarn(source, "Vulkan: Not Available");
        }

        if (OpenCLManager.isAvailable()) {
            sendSuccess(source, "OpenCL: Available");
        } else {
            sendWarn(source, "OpenCL: Not Available");
        }
        return 1;
    }

    private static boolean requireAdmin(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        sendError(source, "Requires permission level 2");
        return false;
    }

    private static boolean parseToggle(String raw) {
        String normalized = raw == null ? "on" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "on", "true", "enable", "enabled", "1" -> true;
            case "off", "false", "disable", "disabled", "0" -> false;
            default -> throw new IllegalArgumentException("Expected on/off, true/false, enable/disable, or 1/0");
        };
    }

    private static String backendLabel(GpuBackendType backendType) {
        return switch (backendType) {
            case CPU -> "CPU";
            case OPENCL -> "OpenCL";
            case VULKAN -> "Vulkan";
        };
    }

    private static MutableComponent prefixed(MutableComponent content) {
        return Component.literal("[Quantified] ").withStyle(style -> style.withColor(COLOR_PREFIX).withBold(true)).append(content);
    }

    private static MutableComponent colored(String text, int color) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(color));
    }

    private static MutableComponent link(String url) {
        Style style = Style.EMPTY
            .withColor(COLOR_PRIMARY)
            .withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        return Component.literal(url).setStyle(style);
    }

    private static void sendInfo(CommandSourceStack source, String message) {
        source.sendSuccess(() -> prefixed(colored(message, COLOR_PRIMARY)), false);
    }

    private static void sendSuccess(CommandSourceStack source, String message) {
        source.sendSuccess(() -> prefixed(colored(message, COLOR_SUCCESS)), false);
    }

    private static void sendWarn(CommandSourceStack source, String message) {
        source.sendSuccess(() -> prefixed(colored(message, COLOR_WARN)), false);
    }

    private static void sendError(CommandSourceStack source, String message) {
        source.sendFailure(prefixed(colored(message, COLOR_ERROR)));
    }
}
