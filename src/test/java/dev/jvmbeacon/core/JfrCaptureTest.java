package dev.jvmbeacon.core;

import org.junit.jupiter.api.Test;
import javax.management.MBeanServerConnection;
import javax.management.openmbean.CompositeData;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class JfrCaptureTest {
    @Test void cancelledLateCreationNeverStartsAndReleasesItsId() throws Exception {
        var entered = new CountDownLatch(1); var unblock = new CountDownLatch(1);
        List<String> operations = new CopyOnWriteArrayList<>();
        var server = (MBeanServerConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MBeanServerConnection.class}, (p, method, args) -> {
                    if (method.getName().equals("invoke")) {
                        String operation = (String) args[1]; operations.add(operation);
                        if (operation.equals("newRecording")) {
                            entered.countDown(); assertTrue(unblock.await(5, TimeUnit.SECONDS)); return 42L;
                        }
                        if (operation.equals("closeRecording")) assertEquals(42L, ((Object[]) args[2])[0]);
                        return null;
                    }
                    throw new AssertionError(method.getName());
                });
        var capture = new JfrCapture(server);
        var worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> call = worker.submit(() -> assertThrows(java.io.InterruptedIOException.class, () -> capture.start("default", 5)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            capture.cancel(); unblock.countDown(); call.get(5, TimeUnit.SECONDS);
            capture.close(); capture.close();
            assertEquals(List.of("newRecording", "closeRecording"), operations);
        } finally { unblock.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void failedSetupRetainsOwnershipAndNeverRetriesOrStarts() throws Exception {
        List<String> operations = new ArrayList<>();
        var server = (MBeanServerConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MBeanServerConnection.class}, (p, method, args) -> {
                    if (method.getName().equals("invoke")) {
                        String operation = (String) args[1]; operations.add(operation);
                        if (operation.equals("newRecording")) return 7L;
                        if (operation.equals("setRecordingOptions")) throw new SecurityException("Denied");
                        return null;
                    }
                    throw new AssertionError(method.getName());
                });
        try (var capture = new JfrCapture(server)) {
            assertThrows(SecurityException.class, () -> capture.start("profile", 5));
            assertThrows(IllegalStateException.class, () -> capture.start("default", 5));
        }
        assertEquals(List.of("newRecording", "setRecordingOptions", "closeRecording"), operations);
    }

    @Test void missingCapabilityDoesNotInitializeRecorder() throws Exception {
        var server = (MBeanServerConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MBeanServerConnection.class}, (p, method, args) -> {
                    assertEquals("isRegistered", method.getName()); return false;
                });
        try (var capture = new JfrCapture(server)) { assertFalse(capture.inspect().supported()); }
    }
}
