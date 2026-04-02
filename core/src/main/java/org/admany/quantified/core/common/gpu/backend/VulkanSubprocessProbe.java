package org.admany.quantified.core.common.gpu.backend;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.admany.quantified.core.common.util.LwjglRuntimeTuning;
import org.admany.quantified.core.common.util.QuantifiedPaths;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

final class VulkanSubprocessProbe {

    private static final String PROBE_ROOT_RESOURCE = "quantified/embedded/vulkanProbe";
    private static final String PROBE_INDEX_RESOURCE = PROBE_ROOT_RESOURCE + "/classpath.index";
    private static final String PROBE_RESOURCE_SUFFIX = ".bin";
    private static final String PROBE_ENTRYPOINT = "org.admany.quantified.vulkan.probe.VulkanProbeEntrypoint";
    private static final long PROBE_TIMEOUT_SECONDS = 20L;
    private static final int PROBE_LWJGL_STACK_KB = 16 * 1024;
    private static final int PROBE_LWJGL_STACK_BYTES = PROBE_LWJGL_STACK_KB * 1024;
    private static final String PROBE_HEAP_MAX = "256m";
    private static final String PROBE_DIRECT_MEMORY_MAX = "128m";
    private static final String DEFAULT_VK_LOADER_DEBUG = System.getProperty("quantified.vulkan.loaderDebug", "error");

    private VulkanSubprocessProbe() {
    }

    static Result run(Logger logger, int probeId) {
        Objects.requireNonNull(logger, "logger");
        try {
            ExtractedProbeBundle probeBundle = extractProbeBundle();
            String javaExecutable = currentJavaExecutable();
            LwjglRuntimeTuning.ensureConfigured();
            List<String> command = new ArrayList<>();
            command.add(javaExecutable);
            command.add("-Xms16m");
            command.add("-Xmx" + PROBE_HEAP_MAX);
            command.add("-XX:MaxDirectMemorySize=" + PROBE_DIRECT_MEMORY_MAX);
            command.add("-Xss16m");
            command.add("-Dorg.lwjgl.system.stackSize=" + PROBE_LWJGL_STACK_KB);
            command.add("-Dquantified.lwjgl.stackSizeKb=" + PROBE_LWJGL_STACK_KB);
            command.add("-Dquantified.lwjgl.stackSizeBytes=" + PROBE_LWJGL_STACK_BYTES);
            addSystemPropertyArg(command, "java.library.path");
            String lwjglLibraryPath = System.getProperty("org.lwjgl.librarypath");
            if (lwjglLibraryPath != null && !lwjglLibraryPath.isBlank()) {
                command.add("-Dorg.lwjgl.librarypath=" + lwjglLibraryPath);
                command.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + lwjglLibraryPath);
            } else {
                addSystemPropertyArg(command, "org.lwjgl.system.SharedLibraryExtractPath");
            }
            command.add("-cp");
            command.add(probeBundle.classpath());
            command.add(PROBE_ENTRYPOINT);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.environment().put("VK_LOADER_LAYERS_DISABLE", "~implicit~");
            builder.environment().putIfAbsent("VK_LOADER_DEBUG", DEFAULT_VK_LOADER_DEBUG);
            builder.environment().remove("VK_INSTANCE_LAYERS");
            builder.environment().remove("VK_LOADER_LAYERS_ENABLE");
            builder.environment().remove("VK_LOADER_LAYERS_ALLOW");

            logger.info(prefix(probeId) + "Launching isolated Vulkan probe: " + String.join(" ", command)
                + " [VK_LOADER_LAYERS_DISABLE=~implicit~, VK_LOADER_DEBUG=" + DEFAULT_VK_LOADER_DEBUG + "]");
            Process process = builder.start();
            List<String> outputLines = new CopyOnWriteArrayList<>();
            Thread stdoutReader = startStdoutReader(logger, probeId, process, outputLines);
            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                waitForReader(stdoutReader);
                String lastLine = outputLines.isEmpty() ? "<none>" : outputLines.get(outputLines.size() - 1);
                logger.warn(prefix(probeId) + "Vulkan subprocess probe timed out; last output: " + lastLine);
                return Result.failed("Vulkan subprocess probe timed out after " + PROBE_TIMEOUT_SECONDS
                    + " seconds (last output: " + lastLine + ")", 0, 0, List.of());
            }
            waitForReader(stdoutReader);
            int exitCode = process.exitValue();
            String output = joinOutput(outputLines);
            return parseResult(exitCode, output);
        } catch (IOException exception) {
            return Result.failed("Failed to launch Vulkan subprocess probe: " + exception.getMessage(), 0, 0, List.of());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.failed("Interrupted while waiting for Vulkan subprocess probe", 0, 0, List.of());
        } catch (Throwable throwable) {
            return Result.failed("Unexpected Vulkan subprocess probe failure: " + throwable.getMessage(), 0, 0, List.of());
        }
    }

    private static ExtractedProbeBundle extractProbeBundle() throws IOException {
        Path bundleRoot = QuantifiedPaths.getCacheDir().resolve("tools").resolve("vulkanProbe");
        Files.createDirectories(bundleRoot);
        Path currentRuntimePath = currentRuntimePath();
        List<String> relativeEntries = readClasspathIndex();
        if (relativeEntries.isEmpty()) {
            throw new IOException("Embedded Vulkan probe classpath index missing or empty: " + PROBE_INDEX_RESOURCE);
        }
        List<Path> extractedEntries = new ArrayList<>(relativeEntries.size());
        for (String relativeEntry : relativeEntries) {
            Path destination = bundleRoot.resolve(relativeEntry.replace('/', File.separatorChar));
            Files.createDirectories(destination.getParent());
            String resourcePath = PROBE_ROOT_RESOURCE + "/" + relativeEntry + PROBE_RESOURCE_SUFFIX;
            try (InputStream stream = VulkanSubprocessProbe.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    throw new IOException("Embedded Vulkan probe resource missing: " + resourcePath);
                }
                Files.copy(stream, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            extractedEntries.add(destination);
        }
        List<String> classpathEntries = new ArrayList<>(extractedEntries.size() + 1);
        classpathEntries.add(currentRuntimePath.toAbsolutePath().toString());
        classpathEntries.addAll(extractedEntries.stream()
            .map(path -> path.toAbsolutePath().toString())
            .toList());
        String classpath = classpathEntries.stream()
            .reduce((left, right) -> left + File.pathSeparator + right)
            .orElseThrow(() -> new IOException("Failed to build Vulkan probe classpath"));
        return new ExtractedProbeBundle(bundleRoot, classpath);
    }

    private static Path currentRuntimePath() throws IOException {
        List<String> attempted = new ArrayList<>();
        try {
            URL codeSource = VulkanSubprocessProbe.class.getProtectionDomain().getCodeSource() != null
                ? VulkanSubprocessProbe.class.getProtectionDomain().getCodeSource().getLocation()
                : null;
            Path fromCodeSource = resolveRuntimeLocation(codeSource, attempted);
            if (fromCodeSource != null) {
                return fromCodeSource;
            }
        } catch (URISyntaxException exception) {
            attempted.add("codesource-uri-error:" + exception.getMessage());
        }
        try {
            Path fromVkResource = resolveRuntimeLocation(
                VulkanSubprocessProbe.class.getClassLoader().getResource("org/lwjgl/vulkan/VK.class"),
                attempted);
            if (fromVkResource != null) {
                return fromVkResource;
            }
        } catch (URISyntaxException exception) {
            attempted.add("vk-resource-uri-error:" + exception.getMessage());
        }
        try {
            Path fromSelfResource = resolveRuntimeLocation(
                VulkanSubprocessProbe.class.getResource("VulkanSubprocessProbe.class"),
                attempted);
            if (fromSelfResource != null) {
                return fromSelfResource;
            }
        } catch (URISyntaxException exception) {
            attempted.add("self-resource-uri-error:" + exception.getMessage());
        }
        throw new IOException("Failed to resolve current Quantified runtime location from " + attempted);
    }

    private static Path resolveRuntimeLocation(URL location, List<String> attempted) throws URISyntaxException {
        if (location == null) {
            attempted.add("<null>");
            return null;
        }
        String raw = URLDecoder.decode(location.toString(), StandardCharsets.UTF_8);
        attempted.add(raw);

        String trimmed = trimToArchive(raw);
        if (trimmed.startsWith("jar:")) {
            trimmed = trimmed.substring(4);
        }
        if (trimmed.startsWith("union:")) {
            trimmed = trimmed.substring(6);
        }

        Path candidate = tryPathFromLocation(trimmed);
        if (candidate != null && Files.exists(candidate)) {
            return candidate;
        }
        attempted.add("missing:" + trimmed);
        return null;
    }

    private static String trimToArchive(String raw) {
        String value = raw;
        int bang = value.indexOf('!');
        if (bang >= 0) {
            value = value.substring(0, bang);
        }
        int jarIndex = value.toLowerCase(Locale.ROOT).indexOf(".jar");
        if (jarIndex >= 0) {
            value = value.substring(0, jarIndex + 4);
        }
        int hashIndex = value.indexOf('#');
        if (hashIndex >= 0 && value.toLowerCase(Locale.ROOT).contains(".jar#")) {
            value = value.substring(0, hashIndex);
        }
        return value;
    }

    private static Path tryPathFromLocation(String location) throws URISyntaxException {
        if (location == null || location.isBlank()) {
            return null;
        }
        if (location.startsWith("file:")) {
            return Path.of(URI.create(location)).normalize();
        }
        String normalized = location.replace('/', File.separatorChar);
        if (normalized.length() >= 3
            && normalized.charAt(0) == File.separatorChar
            && Character.isLetter(normalized.charAt(1))
            && normalized.charAt(2) == ':') {
            normalized = normalized.substring(1);
        }
        return Path.of(normalized).normalize();
    }

    private static List<String> readClasspathIndex() throws IOException {
        try (InputStream stream = VulkanSubprocessProbe.class.getClassLoader().getResourceAsStream(PROBE_INDEX_RESOURCE)) {
            if (stream == null) {
                return List.of();
            }
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            List<String> entries = new ArrayList<>();
            for (String line : content.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    entries.add(trimmed);
                }
            }
            return entries;
        }
    }

    private static String currentJavaExecutable() {
        return ProcessHandle.current().info().command()
            .orElseGet(() -> System.getProperty("java.home") + System.getProperty("file.separator") + "bin"
                + System.getProperty("file.separator") + "java");
    }

    private static void addSystemPropertyArg(List<String> command, String propertyName) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            return;
        }
        command.add("-D" + propertyName + "=" + value);
    }

    private static Thread startStdoutReader(Logger logger,
                                            int probeId,
                                            Process process,
                                            List<String> outputLines) {
        Thread readerThread = new Thread(() -> {
            try (InputStream stream = process.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputLines.add(line);
                    if (!line.isBlank() && !shouldSuppressSubprocessLine(line)) {
                        logger.info(prefix(probeId) + "[Subprocess] " + line);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "Quantified-Vulkan-Probe-Stdout");
        readerThread.setDaemon(true);
        readerThread.start();
        return readerThread;
    }

    private static void waitForReader(Thread stdoutReader) {
        if (stdoutReader == null) {
            return;
        }
        try {
            stdoutReader.join(1000L);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static String joinOutput(List<String> outputLines) {
        if (outputLines == null || outputLines.isEmpty()) {
            return "";
        }
        return String.join(System.lineSeparator(), outputLines);
    }

    private static boolean shouldSuppressSubprocessLine(String line) {
        if (line == null || line.isBlank()) {
            return true;
        }
        if (!line.contains("[Vulkan Loader] WARNING")) {
            return false;
        }
        return line.contains("does not conform to naming standard")
            || line.contains("forced disabled because name matches filter of env var 'VK_LOADER_LAYERS_DISABLE'")
            || line.contains("forced disabled because name matches filter of env var");
    }

    private static Result parseResult(int exitCode, String output) {
        String jsonPayload = extractJsonPayload(output);
        if (jsonPayload == null) {
            return Result.failed("Vulkan subprocess probe returned no JSON payload (exit=" + exitCode + ")", 0, 0, List.of());
        }
        JsonObject root = JsonParser.parseString(jsonPayload).getAsJsonObject();
        boolean ok = getBoolean(root, "ok", exitCode == 0);
        int maxApiVersion = getInt(root, "maxApiVersion", 0);
        int selectedApiVersion = getInt(root, "selectedApiVersion", 0);
        String failureReason = getString(root, "failure").orElse(null);
        List<ResultDevice> devices = parseDevices(root.getAsJsonArray("devices"));
        if (ok) {
            return Result.success(maxApiVersion, selectedApiVersion, devices);
        }
        String reason = failureReason != null && !failureReason.isBlank()
            ? failureReason
            : "Vulkan subprocess probe failed with exit code " + exitCode;
        return Result.failed(reason, maxApiVersion, selectedApiVersion, devices);
    }

    private static String extractJsonPayload(String output) {
        if (output == null) {
            return null;
        }
        String trimmed = output.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        String[] lines = trimmed.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                return line;
            }
        }
        return null;
    }

    private static List<ResultDevice> parseDevices(JsonArray devicesArray) {
        if (devicesArray == null || devicesArray.isEmpty()) {
            return List.of();
        }
        List<ResultDevice> devices = new ArrayList<>(devicesArray.size());
        for (JsonElement element : devicesArray) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject device = element.getAsJsonObject();
            devices.add(new ResultDevice(
                getString(device, "id").orElse("unknown-vulkan-device"),
                getString(device, "name").orElse("Unknown Vulkan Device"),
                getString(device, "vendor").orElse("Unknown"),
                getInt(device, "deviceType", 0),
                getLong(device, "localMemoryBytes", 0L),
                getBoolean(device, "softwareAdapter", false)
            ));
        }
        return devices;
    }

    private static Optional<String> getString(JsonObject root, String key) {
        if (root == null || !root.has(key) || root.get(key).isJsonNull()) {
            return Optional.empty();
        }
        String value = root.get(key).getAsString();
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static int getInt(JsonObject root, String key, int fallback) {
        if (root == null || !root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return root.get(key).getAsInt();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long getLong(JsonObject root, String key, long fallback) {
        if (root == null || !root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return root.get(key).getAsLong();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean getBoolean(JsonObject root, String key, boolean fallback) {
        if (root == null || !root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return root.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String prefix(int probeId) {
        return "[Vulkan][Probe " + probeId + "] ";
    }

    record Result(boolean ok,
                  String failureReason,
                  int maxApiVersion,
                  int selectedApiVersion,
                  List<ResultDevice> devices) {

        static Result success(int maxApiVersion, int selectedApiVersion, List<ResultDevice> devices) {
            return new Result(true, null, maxApiVersion, selectedApiVersion, devices != null ? List.copyOf(devices) : List.of());
        }

        static Result failed(String failureReason, int maxApiVersion, int selectedApiVersion, List<ResultDevice> devices) {
            return new Result(false, failureReason, maxApiVersion, selectedApiVersion, devices != null ? List.copyOf(devices) : List.of());
        }
    }

    record ResultDevice(String id,
                        String name,
                        String vendor,
                        int deviceType,
                        long localMemoryBytes,
                        boolean softwareAdapter) {

        String normalizedId() {
            String value = (vendor + "-" + name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
            return value.isBlank() ? id : value;
        }
    }

    private record ExtractedProbeBundle(Path rootDir, String classpath) {
    }
}
