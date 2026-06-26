package org.admany.quantified.core.fabric.v26;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import org.admany.quantified.api.compute.GpuBackendPreference;
import org.admany.quantified.api.compute.GpuBackendType;
import org.admany.quantified.core.common.commands.QuantifiedCommandService;
import org.admany.quantified.core.common.gpu.backend.GpuBackendRouter;
import org.admany.quantified.core.common.gpu.backend.VulkanExecutionSupport;
import org.admany.quantified.core.common.gpu.backend.VulkanRuntime;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.admany.quantified.core.common.telemetry.TelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.function.Supplier;

public final class QuantifiedFabricCommand26 {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuantifiedFabricCommand26.class);
    private static final int COLOR_PREFIX = 0xAA00AA;
    private static final int COLOR_PRIMARY = 0xDA70D6;
    private static final int COLOR_SUCCESS = 0x69F0AE;
    private static final int COLOR_WARN = 0xFFC857;
    private static final int COLOR_ERROR = 0xFF6F61;

    private QuantifiedFabricCommand26() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        try {
            dispatcher.register(Commands.literal("quantified")
                .requires(QuantifiedFabricCommand26::canUse)
                .then(Commands.literal("webpanel")
                    .executes(ctx -> webPanel(ctx.getSource(), "on"))
                    .then(Commands.argument("state", StringArgumentType.word())
                        .executes(ctx -> webPanel(ctx.getSource(), StringArgumentType.getString(ctx, "state")))))
                .then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource())))
                .then(Commands.literal("mods").executes(ctx -> mods(ctx.getSource())))
                .then(Commands.literal("gpu").executes(ctx -> gpuStatus(ctx.getSource()))));
            LOGGER.info("Quantified command tree registered for Fabric 26 bootstrap");
        } catch (Throwable t) {
            LOGGER.error("Failed to register Quantified Fabric 26 commands - skipping registration to avoid game crash", t);
        }
    }

    private static int webPanel(CommandSourceStack source, String rawState) {
        LOGGER.info("Executing /quantified webpanel with state={}", rawState);
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
            QuantifiedCommandService.DashboardResult result = QuantifiedCommandService.enableDashboardForCommand();
            LOGGER.info("Fabric 26 /quantified webpanel result: success={}, message='{}', url='{}'",
                result.success(), result.message(), result.url());
            if (!result.success()) {
                sendError(source, result.message());
                return 0;
            }
            MutableComponent message = colored(result.message(), COLOR_SUCCESS);
            message.append(Component.literal(" | "));
            message.append(colored("Open UI: ", COLOR_PRIMARY));
            message.append(link(result.url()));
            deliver(source, prefixed(message), false);
        } else {
            QuantifiedCommandService.DashboardResult result = QuantifiedCommandService.disableDashboard();
            sendInfo(source, result.message());
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
        sendInfo(source, "Registered Mods: " + QuantifiedCoreRuntime.getRegisteredMods().size());
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
            String status = info.active ? "active" : "inactive";
            long age = System.currentTimeMillis() - info.lastActivity;
            String ageStr = age < 60000 ? (age / 1000) + "s ago" : (age / 60000) + "m ago";
            sendInfo(source, info.modId + " v" + info.version + " | " + status + " | Last: " + ageStr);
        }
        return 1;
    }

    private static int gpuStatus(CommandSourceStack source) {
        boolean vulkanBindingsPresent = VulkanRuntime.hasBindings();
        VulkanRuntime.RuntimeMode vulkanRuntimeMode = VulkanRuntime.runtimeMode();
        boolean vulkanRuntimeInitialized = VulkanExecutionSupport.hasExecutableRuntime();
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

        sendInfo(source, "GPU Acceleration Status");
        sendInfo(source, "Preferred: " + preferredBackend.displayLabel() + " | Active: " + backendLabel(activeBackend));
        if (vulkanProbeAvailable) {
            sendSuccess(source, "Vulkan: Available");
            sendInfo(source, "Vulkan device: " + VulkanExecutionSupport.deviceName());
            sendInfo(source, "Vulkan runtime: " + (vulkanBindingsPresent ? "Native" : "Isolated bundled") + " | "
                + (vulkanRuntimeInitialized ? "Ready" : "Deferred until first use"));
        } else {
            sendWarn(source, "Vulkan: Not Available");
        }
        sendInfo(source, "OpenCL: " + (OpenCLManager.isAvailable() ? "Available" : "Not Available"));
        return 1;
    }

    private static boolean requireAdmin(CommandSourceStack source) {
        if (FabricCommandDevAccess26.hasDevAccess(source)) {
            return true;
        }
        sendError(source, "Developer features require operator permission (level 2+)");
        return false;
    }

    private static boolean canUse(CommandSourceStack source) {
        return FabricCommandDevAccess26.hasDevAccess(source);
    }

    private static MutableComponent prefixed(MutableComponent content) {
        return Component.literal("[Quantified] ")
            .withStyle(style -> style.withColor(COLOR_PREFIX).withBold(true))
            .append(content);
    }

    private static MutableComponent colored(String text, int color) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(color));
    }

    private static MutableComponent link(String url) {
        return Component.literal(url).withStyle(style -> style
            .withColor(COLOR_PRIMARY)
            .withUnderlined(true)
            .withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
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

    private static void deliver(CommandSourceStack source, MutableComponent message, boolean failure) {
        try {
            ServerPlayer player = source.getPlayer();
            if (player != null) {
                player.sendSystemMessage(message);
                return;
            }
        } catch (Throwable t) {
            LOGGER.warn("Direct Fabric 26 player delivery lookup failed for Quantified command message", t);
        }
        try {
            if (failure) {
                source.sendFailure(message);
            } else {
                source.sendSuccess((Supplier<Component>) () -> message, false);
            }
        } catch (Throwable primaryFailure) {
            LOGGER.warn("Primary Fabric 26 command delivery failed, trying system message", primaryFailure);
            try {
                source.sendSystemMessage(message);
            } catch (Throwable secondaryFailure) {
                LOGGER.warn("Unable to deliver Quantified Fabric 26 command message", secondaryFailure);
            }
        }
    }

    private static boolean parseToggle(String raw) {
        String normalized = raw == null ? "on" : raw.trim().toLowerCase();
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
}
