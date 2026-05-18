package net.fabricmc.loader.api;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FabricLoader {

    private static final FabricLoader INSTANCE = new FabricLoader();

    private final Map<String, ModContainer> mods = new LinkedHashMap<>();

    private FabricLoader() {}

    public static FabricLoader getInstance() {
        return INSTANCE;
    }

    public Optional<ModContainer> getModContainer(String modId) {
        return Optional.ofNullable(mods.get(modId));
    }

    public Collection<ModContainer> getAllMods() {
        return List.copyOf(mods.values());
    }

    public static void installTestMod(String modId, String name, String version, Path rootPath) {
        INSTANCE.mods.put(modId, new ModContainer(
            new ModMetadata(modId, name, new Version(version)),
            List.of(rootPath)
        ));
    }

    public static void clearTestMods() {
        INSTANCE.mods.clear();
    }

    public static final class ModContainer {
        private final ModMetadata metadata;
        private final List<Path> rootPaths;

        public ModContainer(ModMetadata metadata, List<Path> rootPaths) {
            this.metadata = metadata;
            this.rootPaths = rootPaths;
        }

        public ModMetadata getMetadata() {
            return metadata;
        }

        public Iterable<Path> getRootPaths() {
            return rootPaths;
        }
    }

    public static final class ModMetadata {
        private final String id;
        private final String name;
        private final Version version;

        public ModMetadata(String id, String name, Version version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Version getVersion() {
            return version;
        }
    }

    public static final class Version {
        private final String friendlyString;

        public Version(String friendlyString) {
            this.friendlyString = friendlyString;
        }

        public String getFriendlyString() {
            return friendlyString;
        }

        @Override
        public String toString() {
            return friendlyString;
        }
    }
}
