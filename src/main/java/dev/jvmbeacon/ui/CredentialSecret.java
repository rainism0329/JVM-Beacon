package dev.jvmbeacon.ui;

import com.intellij.credentialStore.Credentials;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Mutable result returned from PasswordSafe; late or unused results are explicitly cleared. */
final class CredentialSecret implements BeaconExecutors.ManagedConnection {
    private final AtomicReference<char[]> value;
    CredentialSecret(char[] value) { this.value = new AtomicReference<>(value); }
    static CredentialSecret from(Credentials credentials) {
        return new CredentialSecret(credentials == null || credentials.getPassword() == null
                ? null : credentials.getPassword().toCharArray());
    }
    boolean isPresent() { return value.get() != null; }
    char[] take() { return value.getAndSet(null); }
    @Override public void close() {
        char[] owned = take();
        if (owned != null) Arrays.fill(owned, '\0');
    }
}
