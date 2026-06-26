package org.admany.quantified.core.forge.v26;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraft.commands.CommandSourceStack;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.admany.quantified.core.forge.commands.QuantifiedCommand;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class QuantifiedForge26Bootstrap {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(QuantifiedForge26Bootstrap.class);
    private static volatile boolean installed;

    private QuantifiedForge26Bootstrap() {
    }

    public static void install(FMLJavaModLoadingContext context) {
        if (installed) {
            return;
        }
        synchronized (QuantifiedForge26Bootstrap.class) {
            if (installed) {
                return;
            }

            installModBusListener(context, "net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent", QuantifiedForge26Bootstrap::onCommonSetup);
            installModBusListener(context, "net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent", QuantifiedForge26Bootstrap::onClientSetup);
            installStaticBusListener("net.minecraftforge.event.server.ServerStartingEvent", QuantifiedForge26Bootstrap::onServerStarting);
            installStaticBusListener("net.minecraftforge.event.RegisterCommandsEvent", QuantifiedForge26Bootstrap::onRegisterCommands);
            installStaticBusListener("net.minecraftforge.event.server.ServerStoppingEvent", ignored -> QuantifiedCoreRuntime.onServerStopping());

            bootstrapCore();
            installed = true;
        }
    }

    private static void onCommonSetup(Object event) {
        bootstrapCore();
    }

    private static void onClientSetup(Object event) {
        QuantifiedCoreRuntime.onClientSetup(LOGGER);
        installStaticBusListener(
            "net.minecraftforge.event.TickEvent$RenderTickEvent$Pre",
            ignored -> QuantifiedCoreRuntime.onRenderTickStart(LOGGER)
        );
    }

    private static void onServerStarting(Object event) {
        try {
            Object server = event.getClass().getMethod("getServer").invoke(event);
            QuantifiedCoreRuntime.onServerStarting((Executor) server);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read Forge 26 server starting event", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void onRegisterCommands(Object event) {
        try {
            Object dispatcher = event.getClass().getMethod("getDispatcher").invoke(event);
            QuantifiedCommand.register((CommandDispatcher<CommandSourceStack>) dispatcher);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to register Forge 26 commands", e);
        }
        LOGGER.debug("Quantified commands registered.");
    }

    private static void installModBusListener(FMLJavaModLoadingContext context, String eventClassName, Consumer<Object> listener) {
        try {
            Object modBusGroup = context.getClass().getMethod("getModBusGroup").invoke(context);
            Class<?> busGroupClass = Class.forName("net.minecraftforge.eventbus.api.bus.BusGroup");
            Object eventBus = Class.forName(eventClassName)
                .getMethod("getBus", busGroupClass)
                .invoke(null, modBusGroup);
            addListener(eventBus, listener);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to install Forge 26 mod-bus listener for " + eventClassName, e);
        }
    }

    private static void installStaticBusListener(String eventClassName, Consumer<Object> listener) {
        try {
            Field busField = Class.forName(eventClassName).getField("BUS");
            addListener(busField.get(null), listener);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to install Forge 26 static listener for " + eventClassName, e);
        }
    }

    private static void addListener(Object eventBus, Consumer<Object> listener) throws ReflectiveOperationException {
        Method addListener = eventBus.getClass().getMethod("addListener", Consumer.class);
        addListener.invoke(eventBus, listener);
    }

    private static void bootstrapCore() {
        QuantifiedCoreRuntime.bootstrap(
            LOGGER,
            new QuantifiedCoreRuntime.PlatformPaths(FMLPaths.GAMEDIR.get(), FMLPaths.CONFIGDIR.get())
        );
    }
}
