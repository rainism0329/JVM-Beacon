package dev.jvmbeacon.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmxTlsIntegrationTest {
    @Test
    @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void trustedTlsAndAllRejectionBoundariesUseIsolatedProcesses() throws Exception {
        var results = TlsSmokeMain.runAll();
        assertEquals(6, results.stream().filter(value -> value.startsWith("TLS_CASE_PASS ")).count());
        assertTrue(results.contains("TLS_PARENT_TRUST_UNCHANGED"));
    }
}
