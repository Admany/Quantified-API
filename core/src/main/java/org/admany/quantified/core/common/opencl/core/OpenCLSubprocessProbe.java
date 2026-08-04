package org.admany.quantified.core.common.opencl.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.admany.quantified.core.common.util.LwjglRuntimeTuning;
import org.admany.quantified.core.common.util.QuantifiedPaths;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.zip.GZIPInputStream;

final class OpenCLSubprocessProbe {

    private static final String PROBE_ROOT_RESOURCE = "quantified/embedded/openclProbe";
    private static final String PROBE_INDEX_RESOURCE = PROBE_ROOT_RESOURCE + "/classpath.index";
    private static final String PROBE_RESOURCE_SUFFIX = ".bin";
    private static final String PROBE_ENTRYPOINT = "org.admany.quantified.opencl.probe.OpenCLProbeEntrypoint";
    private static final long PROBE_TIMEOUT_SECONDS = 20L;
    private static final int PROBE_LWJGL_STACK_KB = 16 * 1024;

    private OpenCLSubprocessProbe() {
    }

    static Result run(Logger logger, int probeId) {
        Objects.requireNonNull(logger, "logger");
        try {
            ExtractedProbeBundle probeBundle = extractProbeBundle();
            String javaExecutable = currentJavaExecutable();
            LwjglRuntimeTuning.ensureConfigured();

            List<String> command = new ArrayList<>();
            command.add(javaExecutable);
            LwjglRuntimeTuning.addModernJvmCompatArgs(command);
            command.add("-Xms16m");
            command.add("-Xmx256m");
            command.add("-Xss16m");
            command.add("-Dorg.lwjgl.opencl.explicitInit=true");
            command.add("-Dorg.lwjgl.system.stackSize=" + PROBE_LWJGL_STACK_KB);
            LwjglRuntimeTuning.addIsolatedProbeNativeExtractPath(command, probeBundle.rootDir());
            command.add("-cp");
            command.add(probeBundle.classpath());
            command.add(PROBE_ENTRYPOINT);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            logger.info(prefix(probeId) + "Launching isolated OpenCL probe: " + String.join(" ", command));

            Process process = builder.start();
            List<String> outputLines = new CopyOnWriteArrayList<>();
            Thread stdoutReader = startStdoutReader(logger, probeId, process, outputLines);
            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                waitForReader(stdoutReader);
                String lastLine = outputLines.isEmpty() ? "<none>" : outputLines.get(outputLines.size() - 1);
                return Result.failed("OpenCL subprocess probe timed out after " + PROBE_TIMEOUT_SECONDS
                    + " seconds (last output: " + lastLine + ")", List.of());
            }
            waitForReader(stdoutReader);
            return parseResult(process.exitValue(), joinOutput(outputLines));
        } catch (IOException exception) {
            return Result.failed("Failed to launch OpenCL subprocess probe: " + exception.getMessage(), List.of());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.failed("Interrupted while waiting for OpenCL subprocess probe", List.of());
        } catch (Throwable throwable) {
            return Result.failed("Unexpected OpenCL subprocess probe failure: " + throwable.getMessage(), List.of());
        }
    }

    private static ExtractedProbeBundle extractProbeBundle() throws IOException {
        Path bundleRoot = QuantifiedPaths.getCacheDir().resolve("tools").resolve("openclProbe");
        Files.createDirectories(bundleRoot);
        List<String> relativeEntries = readClasspathIndex();
        if (relativeEntries.isEmpty()) {
            throw new IOException("Embedded OpenCL probe classpath index missing or empty: " + PROBE_INDEX_RESOURCE);
        }
        List<Path> extractedEntries = new ArrayList<>(relativeEntries.size());
        for (String relativeEntry : relativeEntries) {
            Path destination = bundleRoot.resolve(relativeEntry.replace('/', File.separatorChar));
                Files.createDirectories(destination.getParent());
            String resourcePath = PROBE_ROOT_RESOURCE + "/" + relativeEntry + PROBE_RESOURCE_SUFFIX;
            try (InputStream stream = OpenCLSubprocessProbe.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    throw new IOException("Embedded OpenCL probe resource missing: " + resourcePath);
                }
                extractEmbeddedBinary(stream, destination);
            }
            extractedEntries.add(destination);
        }
        LwjglRuntimeTuning.extractIsolatedLinuxCoreNative(extractedEntries, bundleRoot);
        return new ExtractedProbeBundle(
            bundleRoot,
            String.join(File.pathSeparator, extractedEntries.stream().map(path -> path.toAbsolutePath().toString()).toList())
        );
    }

    private static List<String> readClasspathIndex() throws IOException {
        try (InputStream stream = OpenCLSubprocessProbe.class.getClassLoader().getResourceAsStream(PROBE_INDEX_RESOURCE)) {
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

    private static Thread startStdoutReader(Logger logger, int probeId, Process process, List<String> outputLines) {
        Thread readerThread = new Thread(() -> {
            try (InputStream stream = process.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputLines.add(line);
                    if (!line.isBlank()) {
                        logger.info(prefix(probeId) + "[Subprocess] " + line);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "Quantified-OpenCL-Probe-Stdout");
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

    private static void extractEmbeddedBinary(InputStream stream, Path destination) throws IOException {
        byte[] payload = stream.readAllBytes();
        if (payload.length >= 2 && (payload[0] & 0xFF) == 0x1F && (payload[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                Files.copy(gzip, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        Files.write(destination, payload);
    }

    private static String joinOutput(List<String> outputLines) {
        return outputLines == null || outputLines.isEmpty() ? "" : String.join(System.lineSeparator(), outputLines);
    }

    private static Result parseResult(int exitCode, String output) {
        String jsonPayload = extractJsonPayload(output);
        if (jsonPayload == null) {
            return Result.failed("OpenCL subprocess probe returned no JSON payload (exit=" + exitCode + ")", List.of());
        }
        JsonObject root = JsonParser.parseString(jsonPayload).getAsJsonObject();
        boolean ok = getBoolean(root, "ok", exitCode == 0);
        String failureReason = getString(root, "failure").orElse(null);
        List<ResultDevice> devices = parseDevices(root.getAsJsonArray("devices"));
        if (ok) {
            return Result.success(devices);
        }
        return Result.failed(
            failureReason != null && !failureReason.isBlank()
                ? failureReason
                : "OpenCL subprocess probe failed with exit code " + exitCode,
            devices
        );
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
                getString(device, "id").orElse("unknown-opencl-device"),
                getString(device, "name").orElse("Unknown OpenCL Device"),
                getString(device, "vendor").orElse("Unknown"),
                getString(device, "type").orElse("LEGACY"),
                getLong(device, "vramBytes", 0L),
                getInt(device, "computeUnits", 0),
                getBoolean(device, "supportsOpenCL32", false),
                getBoolean(device, "supportsOpenCL12", false)
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
        return "[OpenCL][Probe " + probeId + "] ";
    }

    record Result(boolean ok, String failureReason, List<ResultDevice> devices) {
        static Result success(List<ResultDevice> devices) {
            return new Result(true, null, devices != null ? List.copyOf(devices) : List.of());
        }

        static Result failed(String failureReason, List<ResultDevice> devices) {
            return new Result(false, failureReason, devices != null ? List.copyOf(devices) : List.of());
        }
    }

    record ResultDevice(String id,
                        String name,
                        String vendor,
                        String type,
                        long vramBytes,
                        int computeUnits,
                        boolean supportsOpenCL32,
                        boolean supportsOpenCL12) {
    }

    private record ExtractedProbeBundle(Path rootDir, String classpath) {
    }
}
