package org.admany.quantified.core.common.opencl.gpu.probe;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Supplier;

public final class GpuTelemetryService {

    private static final Logger LOGGER = Logger.getLogger(GpuTelemetryService.class.getName());
    private static final long SAMPLE_STALE_MS = 5_000L;
    private static final long POLL_INTERVAL_MS = 2_000L;
    private static final GpuTelemetryService INSTANCE = new GpuTelemetryService();

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(
        3,
        runnable -> {
            Thread thread = new Thread(runnable, "quantified-gpu-telemetry");
            thread.setDaemon(true);
            return thread;
        }
    );
    private final AtomicReference<String> preferredDeviceName = new AtomicReference<>();

    private final ProbeRunner nvidiaRunner = new ProbeRunner(new NvmlProbe(this::currentPreferredDeviceName));
    private final ProbeRunner amdRunner = new ProbeRunner(new RocmSmiProbe(this::currentPreferredDeviceName));
    private final ProbeRunner intelRunner = new ProbeRunner(new IntelProbe(null));

    private GpuTelemetryService() {
        scheduleRunner(nvidiaRunner, 0L);
        scheduleRunner(amdRunner, 500L);
        scheduleRunner(intelRunner, 1_000L);
    }

    public static GpuTelemetryService getInstance() {
        return INSTANCE;
    }

    public void setPreferredDeviceName(String name) {
        if (name == null) {
            preferredDeviceName.set(null);
            return;
        }
        String trimmed = name.trim();
        preferredDeviceName.set(trimmed.isEmpty() ? null : trimmed);
    }

    private String currentPreferredDeviceName() {
        return preferredDeviceName.get();
    }

    public TelemetrySample latestSample() {
        TelemetrySample sample = nvidiaRunner.latestSample();
        if (sample != null) {
            return sample;
        }
        sample = amdRunner.latestSample();
        if (sample != null) {
            return sample;
        }
        return intelRunner.latestSample();
    }

    public TelemetrySample latestSample(Vendor vendor) {
        return switch (vendor) {
            case NVIDIA -> nvidiaRunner.latestSample();
            case AMD -> amdRunner.latestSample();
            case INTEL -> intelRunner.latestSample();
        };
    }

    private void scheduleRunner(ProbeRunner runner, long initialDelayMs) {
        executor.scheduleWithFixedDelay(
            runner::tick,
            initialDelayMs,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    public enum Vendor {
        NVIDIA,
        AMD,
        INTEL
    }

    public record TelemetrySample(long timestampMs,
                                  Vendor vendor,
                                  String deviceName,
                                  double temperatureC,
                                  long usedMemoryBytes,
                                  long totalMemoryBytes,
                                  double computeUtilization) {
        public boolean isStale(long maxAgeMs) {
            return System.currentTimeMillis() - timestampMs > maxAgeMs;
        }
    }

    private static final class ProbeRunner {
        private final VendorProbe probe;
        private final AtomicReference<TelemetrySample> latest = new AtomicReference<>();
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private volatile long backoffUntil = 0L;

        ProbeRunner(VendorProbe probe) {
            this.probe = probe;
        }

        void tick() {
            if (probe == null) {
                return;
            }
            if (!probe.isSupported()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now < backoffUntil) {
                return;
            }
            try {
                TelemetrySample sample = probe.poll();
                if (sample != null) {
                    latest.set(sample);
                }
                consecutiveFailures.set(0);
            } catch (Throwable throwable) {
                int failures = consecutiveFailures.incrementAndGet();
                long backoff = Math.min(30_000L, (long) Math.pow(2, Math.min(failures, 10)) * 250L);
                backoffUntil = System.currentTimeMillis() + backoff;
                if (failures == 1 || failures == 5 || LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, probe.name() + " telemetry failed (" + failures + "): " + throwable.getMessage(), throwable);
                }
            }
        }

        TelemetrySample latestSample() {
            TelemetrySample sample = latest.get();
            if (sample == null) {
                return null;
            }
            return sample.isStale(SAMPLE_STALE_MS) ? null : sample;
        }
    }

    public interface VendorProbe {
        Vendor vendor();
        String name();
        boolean isSupported();
        TelemetrySample poll() throws Exception;
    }

    // --------- NVIDIA (NVML) [TESTED] -----------

    private static final class NvmlProbe extends AbstractTelemetryProbe {
        private static final int NVML_SUCCESS = 0;
        private static final int NVML_TEMPERATURE_GPU = 0;

        private NvmlLibrary library;
        private final List<DeviceInfo> devices = new ArrayList<>();

        NvmlProbe(Supplier<String> preferredNameSupplier) {
            super(Vendor.NVIDIA, "NVML", preferredNameSupplier);
        }

        @Override
        protected boolean initializeNative() {
            try {
                library = Native.load("nvml", NvmlLibrary.class);
            } catch (Throwable error) {
                LOGGER.log(Level.FINEST, "NVML library unavailable: " + error.getMessage());
                return false;
            }
            int result = library.nvmlInit_v2();
            if (result != NVML_SUCCESS) {
                LOGGER.fine("nvmlInit_v2 failed: " + result);
                return false;
            }
            IntByReference countRef = new IntByReference();
            result = library.nvmlDeviceGetCount(countRef);
            if (result != NVML_SUCCESS || countRef.getValue() <= 0) {
                LOGGER.fine("nvmlDeviceGetCount failed: " + result);
                return false;
            }
            devices.clear();
            for (int idx = 0; idx < countRef.getValue(); idx++) {
                PointerByReference deviceRef = new PointerByReference();
                result = library.nvmlDeviceGetHandleByIndex(idx, deviceRef);
                if (result != NVML_SUCCESS) {
                    continue;
                }
                Pointer handle = deviceRef.getValue();
                if (handle == null) {
                    continue;
                }
                byte[] nameBuffer = new byte[128];
                String name = "NVIDIA GPU";
                if (library.nvmlDeviceGetName(handle, nameBuffer, nameBuffer.length) == NVML_SUCCESS) {
                    name = Native.toString(nameBuffer);
                }
                devices.add(new DeviceInfo(handle, name));
            }
            return !devices.isEmpty();
        }

        @Override
        protected TelemetrySample readSampleInternal() {
            DeviceInfo device = selectDevice();
            if (device == null) {
                return null;
            }
            double temperature = Double.NaN;
            IntByReference tempRef = new IntByReference();
            int rc = library.nvmlDeviceGetTemperature(device.handle(), NVML_TEMPERATURE_GPU, tempRef);
            if (rc == NVML_SUCCESS) {
                temperature = tempRef.getValue();
            }

            NvmlMemory memory = new NvmlMemory();
            rc = library.nvmlDeviceGetMemoryInfo(device.handle(), memory);
            if (rc == NVML_SUCCESS) {
                memory.read();
            } else {
                memory.total = 0L;
                memory.used = 0L;
            }

            NvmlUtilization utilization = new NvmlUtilization();
            rc = library.nvmlDeviceGetUtilizationRates(device.handle(), utilization);
            if (rc == NVML_SUCCESS) {
                utilization.read();
            } else {
                utilization.gpu = 0;
            }

            double compute = Math.min(1.0d, Math.max(0.0d, utilization.gpu / 100.0d));

            return new TelemetrySample(
                System.currentTimeMillis(),
                vendor(),
                device.name(),
                temperature,
                memory.used,
                memory.total,
                compute
            );
        }

        private DeviceInfo selectDevice() {
            if (devices.isEmpty()) {
                return null;
            }
            String preferred = preferredDeviceName();
            if (preferred == null || preferred.isBlank()) {
                return devices.get(0);
            }
            String lowered = preferred.toLowerCase(Locale.ROOT);
            DeviceInfo best = null;
            for (DeviceInfo device : devices) {
                if (device.lowerName().equals(lowered)) {
                    return device;
                }
                if (best == null && device.lowerName().contains(lowered)) {
                    best = device;
                } else if (device.lowerName().contains(lowered) && device.name().length() < best.name().length()) {
                    best = device;
                }
            }
            return best != null ? best : devices.get(0);
        }

        private record DeviceInfo(Pointer handle, String name, String lowerName) {
            DeviceInfo(Pointer handle, String name) {
                this(handle, name, name == null ? "" : name.toLowerCase(Locale.ROOT));
            }
        }
    }

    private interface NvmlLibrary extends Library {
        int nvmlInit_v2();
        int nvmlDeviceGetCount(IntByReference count);
        int nvmlDeviceGetHandleByIndex(int index, PointerByReference device);
        int nvmlDeviceGetName(Pointer device, byte[] name, int length);
        int nvmlDeviceGetTemperature(Pointer device, int sensorType, IntByReference temp);
        int nvmlDeviceGetMemoryInfo(Pointer device, NvmlMemory memory);
        int nvmlDeviceGetUtilizationRates(Pointer device, NvmlUtilization utilization);
    }

    public static class NvmlMemory extends Structure {
        public long total;
        public long free;
        public long used;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("total", "free", "used");
        }
    }

    public static class NvmlUtilization extends Structure {
        public int gpu;
        public int memory;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("gpu", "memory");
        }
    }

    // --------- AMD ROCm SMI [UNTESTED] -----------

    private static final class RocmSmiProbe extends AbstractTelemetryProbe {
        private static final int RSMI_TEMP_TYPE_EDGE = 0;
        private static final int RSMI_TEMP_CURRENT = 0;
        private static final int RSMI_MEM_TYPE_VRAM = 0;

        private RocmSmiLibrary library;
        private final List<DeviceInfo> devices = new ArrayList<>();

        RocmSmiProbe(Supplier<String> preferredNameSupplier) {
            super(Vendor.AMD, "ROCm-SMI", preferredNameSupplier);
        }

        @Override
        protected boolean initializeNative() {
            if (!isLinux()) {
                return false;
            }
            try {
                library = Native.load("rocm_smi64", RocmSmiLibrary.class);
            } catch (Throwable error) {
                LOGGER.log(Level.FINEST, "ROCm SMI library unavailable: " + error.getMessage());
                return false;
            }
            int rc = library.rsmi_init(0);
            if (rc != 0) {
                LOGGER.fine("rsmi_init failed: " + rc);
                return false;
            }
            IntByReference countRef = new IntByReference();
            rc = library.rsmi_num_monitor_devices(countRef);
            if (rc != 0 || countRef.getValue() <= 0) {
                LOGGER.fine("rsmi_num_monitor_devices failed: " + rc);
                return false;
            }
            devices.clear();
            for (int idx = 0; idx < countRef.getValue(); idx++) {
                byte[] nameBuf = new byte[128];
                String name = "AMD GPU";
                if (library.rsmi_dev_name_get(idx, nameBuf, nameBuf.length) == 0) {
                    name = new String(nameBuf, StandardCharsets.UTF_8).trim();
                }
                devices.add(new DeviceInfo(idx, name));
            }
            return !devices.isEmpty();
        }

        @Override
        protected TelemetrySample readSampleInternal() {
            if (library == null) {
                return null;
            }
            DeviceInfo device = selectDevice();
            if (device == null) {
                return null;
            }
            double temperature = Double.NaN;
            LongByReference tempRef = new LongByReference();
            int rc = library.rsmi_dev_temp_metric_get(device.index(), RSMI_TEMP_CURRENT, RSMI_TEMP_TYPE_EDGE, tempRef);
            if (rc == 0) {
                long milliC = tempRef.getValue();
                if (milliC > 0) {
                    temperature = milliC / 1000.0d;
                }
            }

            LongByReference totalRef = new LongByReference();
            LongByReference usedRef = new LongByReference();
            rc = library.rsmi_dev_memory_total_get(device.index(), RSMI_MEM_TYPE_VRAM, totalRef);
            long total = rc == 0 ? totalRef.getValue() : 0L;
            rc = library.rsmi_dev_memory_usage_get(device.index(), RSMI_MEM_TYPE_VRAM, usedRef);
            long used = rc == 0 ? usedRef.getValue() : 0L;

            IntByReference busy = new IntByReference();
            rc = library.rsmi_dev_busy_percent_get(device.index(), busy);
            double compute = rc == 0 ? Math.min(1.0d, Math.max(0.0d, busy.getValue() / 100.0d)) : Double.NaN;

            return new TelemetrySample(
                System.currentTimeMillis(),
                vendor(),
                device.name(),
                temperature,
                used,
                total,
                compute
            );
        }

        private DeviceInfo selectDevice() {
            if (devices.isEmpty()) {
                return null;
            }
            String preferred = preferredDeviceName();
            if (preferred == null || preferred.isBlank()) {
                return devices.get(0);
            }
            String lowered = preferred.toLowerCase(Locale.ROOT);
            DeviceInfo best = null;
            for (DeviceInfo device : devices) {
                if (device.lowerName().equals(lowered)) {
                    return device;
                }
                if (device.lowerName().contains(lowered) && (best == null || device.name().length() < best.name().length())) {
                    best = device;
                }
            }
            return best != null ? best : devices.get(0);
        }

        private record DeviceInfo(int index, String name, String lowerName) {
            DeviceInfo(int index, String name) {
                this(index, name, name == null ? "" : name.toLowerCase(Locale.ROOT));
            }
        }
    }

    private interface RocmSmiLibrary extends Library {
        int rsmi_init(int flags);
        int rsmi_num_monitor_devices(IntByReference deviceCount);
        int rsmi_dev_temp_metric_get(int deviceIndex, int metric, int sensorType, LongByReference temperatureMilliC);
        int rsmi_dev_memory_total_get(int deviceIndex, int memoryType, LongByReference bytes);
        int rsmi_dev_memory_usage_get(int deviceIndex, int memoryType, LongByReference bytes);
        int rsmi_dev_busy_percent_get(int deviceIndex, IntByReference percentage);
        int rsmi_dev_name_get(int deviceIndex, byte[] name, int length);
    }

    // --------- Intel (sysfs / Power Gadget) [UNTESTED] -----------

    private static final class IntelProbe extends AbstractTelemetryProbe {
        private final List<Path> tempSensors = new ArrayList<>();
        private String deviceName = "Intel GPU";

        IntelProbe(Supplier<String> preferredNameSupplier) {
            super(Vendor.INTEL, "IntelSensors", preferredNameSupplier);
        }

        @Override
        protected boolean initializeNative() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (!os.contains("linux")) {
                return false;
            }
            Path drmDir = Paths.get("/sys/class/drm");
            if (!Files.isDirectory(drmDir)) {
                return false;
            }
            try (DirectoryStream<Path> cards = Files.newDirectoryStream(drmDir, "card*")) {
                for (Path card : cards) {
                    Path hwmonRoot = card.resolve("device").resolve("hwmon");
                    if (!Files.isDirectory(hwmonRoot)) {
                        continue;
                    }
                    try (DirectoryStream<Path> hwmons = Files.newDirectoryStream(hwmonRoot, hwmon -> true)) {
                        for (Path hwmon : hwmons) {
                            try (DirectoryStream<Path> temps = Files.newDirectoryStream(hwmon, "temp*_input")) {
                                for (Path sensor : temps) {
                                    tempSensors.add(sensor);
                                }
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
            }
            return !tempSensors.isEmpty();
        }

        @Override
        protected TelemetrySample readSampleInternal() throws IOException {
            if (tempSensors.isEmpty()) {
                return null;
            }
            double temperature = Double.NaN;
            for (Path sensor : tempSensors) {
                double reading = readSensor(sensor);
                if (!Double.isNaN(reading)) {
                    temperature = reading;
                    break;
                }
            }
            if (Double.isNaN(temperature)) {
                return null;
            }
            return new TelemetrySample(
                System.currentTimeMillis(),
                vendor(),
                deviceName,
                temperature,
                0L,
                0L,
                Double.NaN
            );
        }

        private static double readSensor(Path file) {
            try {
                String raw = Files.readString(file).trim();
                if (raw.isEmpty()) {
                    return Double.NaN;
                }
                long milliC = Long.parseLong(raw);
                if (milliC <= 0) {
                    return Double.NaN;
                }
                return milliC / 1000.0d;
            } catch (IOException | NumberFormatException ignored) {
                return Double.NaN;
            }
        }
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
}
