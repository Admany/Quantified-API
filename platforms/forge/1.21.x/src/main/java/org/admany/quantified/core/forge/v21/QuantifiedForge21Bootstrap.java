package org.admany.quantified.core.forge.v21;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.admany.quantified.core.common.platform.QuantifiedCoreRuntime;
import org.admany.quantified.core.forge.commands.QuantifiedCommand;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class QuantifiedForge21Bootstrap {

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(QuantifiedForge21Bootstrap.class);
    private static volatile boolean installed;

    private QuantifiedForge21Bootstrap() {
    }

    public static void install(FMLJavaModLoadingContext context) {
        if (installed) {
            return;
        }
        synchronized (QuantifiedForge21Bootstrap.class) {
            if (installed) {
                return;
            }

            installModBusListener(context, "net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent", QuantifiedForge21Bootstrap::onCommonSetup);
            installModBusListener(context, "net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent", QuantifiedForge21Bootstrap::onClientSetup);
            installStaticBusListener("net.minecraftforge.event.server.ServerStartingEvent", QuantifiedForge21Bootstrap::onServerStarting);
            installStaticBusListener("net.minecraftforge.event.RegisterCommandsEvent", QuantifiedForge21Bootstrap::onRegisterCommands);
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
            throw new IllegalStateException("Failed to read Forge 1.21 server starting event", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void onRegisterCommands(Object event) {
        try {
            Object dispatcher = event.getClass().getMethod("getDispatcher").invoke(event);
            QuantifiedCommand.register((CommandDispatcher<CommandSourceStack>) dispatcher);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to register Forge 1.21 commands", e);
        }
        LOGGER.debug("Quantified commands registered.");
    }

    private static void installModBusListener(FMLJavaModLoadingContext context, String eventClassName, Consumer<Object> listener) {
        try {
            Class<?> eventClass = Class.forName(eventClassName);
            Object modBusGroup = context.getClass().getMethod("getModBusGroup").invoke(context);
            Class<?> busGroupClass = Class.forName("net.minecraftforge.eventbus.api.bus.BusGroup");
            Object eventBus = eventClass
                .getMethod("getBus", busGroupClass)
                .invoke(null, modBusGroup);
            addListener(eventBus, eventClass, listener);
        } catch (ReflectiveOperationException modernFailure) {
            try {
                Class<?> eventClass = Class.forName(eventClassName);
                Object modBus = context.getClass().getMethod("getModEventBus").invoke(context);
                addListener(modBus, eventClass, listener);
            } catch (ReflectiveOperationException legacyFailure) {
                legacyFailure.addSuppressed(modernFailure);
                throw new IllegalStateException("Failed to install Forge 1.21 mod-bus listener for " + eventClassName, legacyFailure);
            }
        }
    }

    private static void installStaticBusListener(String eventClassName, Consumer<Object> listener) {
        try {
            Class<?> eventClass = Class.forName(eventClassName);
            try {
                Field busField = eventClass.getField("BUS");
                addListener(busField.get(null), eventClass, listener);
            } catch (NoSuchFieldException staticBusMissing) {
                Object forgeEventBus = Class.forName("net.minecraftforge.common.MinecraftForge")
                    .getField("EVENT_BUS")
                    .get(null);
                addListener(forgeEventBus, eventClass, listener);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to install Forge 1.21 static listener for " + eventClassName, e);
        }
    }

    private static void addListener(Object eventBus, Class<?> eventClass, Consumer<Object> listener) throws ReflectiveOperationException {
        try {
            Class<?> priorityClass = Class.forName("net.minecraftforge.eventbus.api.EventPriority");
            Object normalPriority = Enum.valueOf((Class<Enum>) priorityClass.asSubclass(Enum.class), "NORMAL");
            Method addListener = eventBus.getClass().getMethod("addListener", priorityClass, boolean.class, Class.class, Consumer.class);
            addListener.invoke(eventBus, normalPriority, false, eventClass, listener);
        } catch (NoSuchMethodException explicitClassOverloadMissing) {
            Method addListener = eventBus.getClass().getMethod("addListener", Consumer.class);
            addListener.invoke(eventBus, listener);
        }
    }

    private static void bootstrapCore() {
        QuantifiedCoreRuntime.bootstrap(
            LOGGER,
            new QuantifiedCoreRuntime.PlatformPaths(FMLPaths.GAMEDIR.get(), FMLPaths.CONFIGDIR.get())
        );
    }
}
