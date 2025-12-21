package org.admany.quantified.core.common.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.admany.quantified.core.common.config.MultithreadingConfig;
import org.admany.quantified.core.common.dev.DeveloperFeatures;
import org.admany.quantified.core.common.opencl.core.OpenCLManager;
import org.admany.quantified.core.common.opencl.gpu.HardwareDetector;
import org.admany.quantified.core.common.telemetry.Metrics;
import org.admany.quantified.core.common.telemetry.TelemetryService;
import org.admany.quantified.core.forge.QuantifiedCoreForge;


public final class QuantifiedCommand {

    private QuantifiedCommand() {}

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(QuantifiedCommand.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        try {
            dispatcher.register(Commands.literal("quantified")
            .then(Commands.literal("webpanel")
                .executes(QuantifiedCommand::webPanel)
                .then(Commands.argument("state", StringArgumentType.word()).executes(ctx -> webPanel(ctx, StringArgumentType.getString(ctx, "state")))))
            .then(Commands.literal("stats").executes(QuantifiedCommand::stats))
            .then(Commands.literal("config").then(Commands.literal("reload").executes(QuantifiedCommand::reloadConfig)))
            .then(Commands.literal("mods").executes(QuantifiedCommand::mods))
            .then(Commands.literal("gpu").executes(QuantifiedCommand::gpuStatus))
            .then(Commands.literal("dev")
                .then(Commands.literal("spotlight")
                    .executes(ctx -> toggleDeveloper(ctx, DeveloperToggle.SPOTLIGHT, ToggleAction.TOGGLE))
                    .then(Commands.argument("state", StringArgumentType.word())
                        .executes(ctx -> toggleDeveloper(ctx, DeveloperToggle.SPOTLIGHT, ToggleAction.EXPLICIT))))
                .then(Commands.literal("status").executes(QuantifiedCommand::developerStatus))
                .then(Commands.literal("opencl-probe").executes(QuantifiedCommand::developerOpenCLProbe))
            )
            );
        } catch (Throwable t) {
            LOGGER.error("Failed to register Quantified commands - skipping registration to avoid game crash", t);
        }
    }

    private static final int COLOR_PREFIX = 0xAA00AA;    // Purple for [Quantified] prefix
    private static final int COLOR_PRIMARY = 0xDA70D6;   // Orchid for main text
    private static final int COLOR_SUCCESS = 0x69F0AE;   // Light green for success
    private static final int COLOR_WARN = 0xFFC857;      // Orange for warnings
    private static final int COLOR_ERROR = 0xFF6F61;     // Red for errors

    private static MutableComponent prefixed(MutableComponent content) {
        return Component.literal("[Quantified] ").withStyle(style -> style.withColor(COLOR_PREFIX).withBold(true)).append(content);
    }

    private static MutableComponent colored(String text, int color) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(color));
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

    private static MutableComponent link(String url) {
        Style style = Style.EMPTY
            .withColor(COLOR_PRIMARY)
            .withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        return Component.literal(url).setStyle(style);
    }

    private static int webPanel(CommandContext<CommandSourceStack> context) {
        return webPanel(context, "on");
    }

    private static int webPanel(CommandContext<CommandSourceStack> context, String rawState) {
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
            boolean wasActive = DeveloperFeatures.isDeveloperModeEnabled();
            DeveloperFeatures.setDeveloperMode(true, true);
            DeveloperFeatures.setDashboardEnabled(true, true);
            int port = MultithreadingConfig.CONFIG.developerDashboardPort;
            String url = "http://127.0.0.1:" + port + "/";
            MutableComponent message = colored(wasActive ? "🔄 Developer dashboard refreshed" : "▶️ Developer dashboard enabled", COLOR_SUCCESS);
            message.append(Component.literal(" │ "));
            message.append(colored("Open UI: ", COLOR_PRIMARY));
            message.append(link(url));
            context.getSource().sendSuccess(() -> prefixed(message), false);
        } else {
            DeveloperFeatures.setDashboardEnabled(false, true);
            DeveloperFeatures.setDeveloperMode(false, true);
            sendInfo(context.getSource(), "⏸️ Developer dashboard disabled");
        }
        return 1;
    }

    private static int stats(CommandContext<CommandSourceStack> context) {
        sendInfo(context.getSource(), "╭─ Performance Statistics ──────────────────╮");

        TelemetryService.SchedulerSnapshot snap = TelemetryService.getLatest();
        if (snap != null) {
            sendInfo(context.getSource(), String.format("│ Tasks: %d submitted, %d executed (%.1f/s) │",
                snap.submitted, snap.executed, snap.execRate));
            sendInfo(context.getSource(), String.format("│ Queue: %d │ Workers: %d foreground, %d background │",
                snap.queueSize, snap.foregroundWorkers, snap.backgroundWorkers));
        } else {
            sendWarn(context.getSource(), "│ No telemetry data available │");
        }

        sendInfo(context.getSource(), "├─ Metrics ──────────────────────────────────┤");
        long requests = Metrics.get("async_requests");
        long cacheHits = Metrics.get("cache_hits");
        long cacheMisses = Metrics.get("cache_misses");
        long networkPackets = Metrics.get("network_packets");

        sendInfo(context.getSource(), String.format("│ Requests: %d │ Cache: %d/%d hits (%.1f%%) │",
            requests, cacheHits, cacheHits + cacheMisses,
            cacheHits + cacheMisses > 0 ? (cacheHits * 100.0 / (cacheHits + cacheMisses)) : 0));
        sendInfo(context.getSource(), String.format("│ Network: %d packets │", networkPackets));

        sendInfo(context.getSource(), "╰────────────────────────────────────────────╯");

        return 1;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
    MultithreadingConfig.initializeGlobals(MultithreadingConfig.LOGGER);
    sendSuccess(context.getSource(), "✓ Configuration reloaded successfully");
        return 1;
    }

    private static int mods(CommandContext<CommandSourceStack> context) {
        var registeredMods = QuantifiedCoreForge.getRegisteredMods();

        sendInfo(context.getSource(), "╭─ Registered Mods ─────────────────────────╮");

        if (registeredMods.isEmpty()) {
            sendWarn(context.getSource(), "│ No mods registered with Quantified API │");
        } else {
            sendInfo(context.getSource(), String.format("│ Total: %d mods │", registeredMods.size()));
            sendInfo(context.getSource(), "├────────────────────────────────────────────┤");

            for (var entry : registeredMods.entrySet()) {
                QuantifiedCoreForge.ModInfo info = entry.getValue();
                String status = info.active ? "● Active" : "○ Inactive";
                long age = System.currentTimeMillis() - info.lastActivity;
                String ageStr = age < 60000 ? (age / 1000) + "s ago" : (age / 60000) + "m ago";

                MutableComponent modLine = Component.literal("│ ")
                    .append(colored(info.modId + " v" + info.version, COLOR_PRIMARY))
                    .append(Component.literal(" │ "))
                    .append(colored(status, info.active ? COLOR_SUCCESS : COLOR_WARN))
                    .append(Component.literal(" │ "))
                    .append(colored("Last: " + ageStr, COLOR_PREFIX));

                context.getSource().sendSuccess(() -> prefixed(modLine), false);
            }
        }

        sendInfo(context.getSource(), "╰────────────────────────────────────────────╯");

        return 1;
    }

    private static int gpuStatus(CommandContext<CommandSourceStack> context) {
        OpenCLManager.RuntimeStatus runtimeStatus = OpenCLManager.runtimeStatus();

        sendInfo(context.getSource(), "╭─ GPU Acceleration Status ─────────────────╮");

        if (!OpenCLManager.isAvailable()) {
            String reason = runtimeStatus.failureReason() != null ? runtimeStatus.failureReason() : "Unknown reason";
            String message = runtimeStatus.isAvailable()
                ? "Runtime loaded but Quantified GPU stack inactive"
                : "Runtime unavailable: " + reason;
            sendWarn(context.getSource(), "│ Status: Not Available │");
            sendWarn(context.getSource(), "│ Reason: " + message + " │");

            HardwareDetector.HardwareStatus hwStatus = HardwareDetector.detailedDetect();
            sendInfo(context.getSource(), String.format("│ OpenCL Conf: %.0f%% │ GPU Conf: %.0f%% │",
                hwStatus.getOpenCLConfidence() * 100, hwStatus.getGPUConfidence() * 100));
            boolean contextOk = hwStatus.getDetectionResults().isContextCreationSuccessful();
            sendInfo(context.getSource(), String.format("│ Context Test: %s │", contextOk ? "✓" : "✗"));

            if (reason.toLowerCase().contains("binding") || reason.toLowerCase().contains("no java opencl")) {
                sendInfo(context.getSource(), "Tip: Include the LWJGL OpenCL binding or JOCL in the mod classpath or run without the sandboxing launcher.");
            }
        } else {
            var status = OpenCLManager.getGPUStatus();
            if (status == null) {
                sendWarn(context.getSource(), "│ Status: Available │");
                sendWarn(context.getSource(), "│ Monitoring: Unavailable │");
            } else {
                long cacheUsedMb = Math.max(0L, status.usedVramBytes()) / (1024 * 1024);
                long cacheBudgetMb = Math.max(1L, status.totalVramBytes()) / (1024 * 1024);
                double vramUsage = status.memoryUtilization() * 100.0;
                double systemUsage = status.systemUsageRatio() * 100.0;
                double computeUsage = status.computeUtilization() * 100.0;
                double temperature = status.temperatureC();

                sendSuccess(context.getSource(), "│ Status: Available │");
                sendInfo(context.getSource(), String.format("│ Cache: %d/%d MB (%.1f%%) │ System VRAM: %.1f%% │ Compute: %.1f%% │ Temp: %.1f°C │",
                    cacheUsedMb, cacheBudgetMb, vramUsage, systemUsage, computeUsage, temperature));
            }
        }

        sendInfo(context.getSource(), "╰────────────────────────────────────────────╯");

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
        sendInfo(context.getSource(), (enable ? "▶️" : "⏸️") + " Developer " + toggle.displayName + " " + (enable ? "enabled" : "disabled"));
        return 1;
    }

    private static int developerStatus(CommandContext<CommandSourceStack> context) {
        if (!requireAdmin(context)) {
            return 0;
        }

        sendInfo(context.getSource(), "--- Developer Features ---");
        sendInfo(context.getSource(), String.format("Mode: %s", flag(DeveloperFeatures.isDeveloperModeEnabled())));
        sendInfo(context.getSource(), "-------------------------");
        sendInfo(context.getSource(), String.format("Hints: ALWAYS ON  | Spotlight: %s", flag(DeveloperFeatures.isModSpotlightEnabled())));
        sendInfo(context.getSource(), "-------------------------");

        return 1;
    }

    private static int developerOpenCLProbe(CommandContext<CommandSourceStack> context) {
        if (!requireAdmin(context)) return 0;
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
                var runtime = OpenCLManager.runtimeStatus();
                String reason = runtime.failureReason() != null ? runtime.failureReason() : "Unknown reason";
                sendWarn(context.getSource(), "OpenCL acceleration is not available on this system: " + reason);
                if (reason.toLowerCase().contains("no java opencl binding") || reason.toLowerCase().contains("binding")) {
                    sendInfo(context.getSource(), "Hint: Ensure the LWJGL OpenCL binding or JOCL is present in the mod classpath, or run without the launcher that sandboxed the JVM.");
                }
            }
        });

        return 1;
    }

    private static boolean requireAdmin(CommandContext<CommandSourceStack> context) {
        if (!context.getSource().hasPermission(2)) {
            sendError(context.getSource(), "❌ Developer features require operator permission (level 2+)");
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
        return value ? "● ON" : "○ OFF";
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

