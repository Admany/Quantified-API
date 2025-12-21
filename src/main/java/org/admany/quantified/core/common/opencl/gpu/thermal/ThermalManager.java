package org.admany.quantified.core.common.opencl.gpu.thermal;

public final class ThermalManager {

    private static final double MAX_TEMPERATURE_C = 90.0d;
    private static final double THERMAL_RECOVERY_DELTA_C = 5.0d;

    private volatile boolean thermallyLimited = false;

    public ThermalManager() {}

    public boolean isThermallyLimited() {
        return thermallyLimited;
    }

    public void updateThermalLimiter(double temperature) {
        if (Double.isNaN(temperature) || temperature <= 0.0d) {
            return;
        }

        double engageThreshold = MAX_TEMPERATURE_C;
        double releaseThreshold = Math.max(engageThreshold - THERMAL_RECOVERY_DELTA_C, engageThreshold * 0.9d);

        if (!thermallyLimited && temperature >= engageThreshold) {
            thermallyLimited = true;
        } else if (thermallyLimited && temperature <= releaseThreshold) {
            thermallyLimited = false;
        }
    }
}