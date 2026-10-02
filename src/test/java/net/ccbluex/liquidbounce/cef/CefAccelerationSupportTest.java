package net.ccbluex.liquidbounce.cef;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CefAccelerationSupportTest {

    // Windows driver store versions from https://gpuopen.com/version-table/
    @ParameterizedTest
    @CsvSource({
        "31.0.21923.1000, false", // 25.5.1 Polaris and Vega
        "31.0.21924.61, false", // 26.1.1 Polaris and Vega
        "32.0.13031.3015, false", // 25.3.1
        "32.0.21025.1024, false", // 25.8.1
        "32.0.21037.1004, false", // 25.12.1 RDNA1 and RDNA2
        "32.0.21041.1000, true", // 26.1.1 RDNA1 and RDNA2
        "32.0.21045.11001, true", // 26.9.2 RDNA1 and RDNA2
        "32.0.22029.9039, false", // 25.12.1
        "32.0.23017.1001, true", // 26.1.1
        "32.0.31041.3013, true", // 26.9.1
        "DriverVersion=32.0.23017.1001, true", // OSHI's WMI fallback
        "unknown, false",
    })
    void amdLeakFix(String driverVersion, boolean fixed) {
        assertEquals(fixed, CefAccelerationSupport.isAmdLeakFixed(driverVersion));
    }

}
