package dev.jvmbeacon.ui;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/** Owns one mutable password copy until discarded or consumed by a worker. Never logs it. */
final class SecretTask<T> implements SessionRunner.OwnedWork<T> {
    @FunctionalInterface interface Work<T> { T run(char[] password) throws Exception; }
    private record Input<T>(char[] password, Work<T> work) { }
    private final AtomicReference<Input<T>> input;

    SecretTask(char[] password, Work<T> work) {
        input = new AtomicReference<>(new Input<>(Objects.requireNonNull(password), Objects.requireNonNull(work)));
    }

    @Override public T call() throws Exception {
        Input<T> owned = input.getAndSet(null);
        if (owned == null) throw new CancellationException("Credential task was discarded.");
        try { return owned.work().run(owned.password()); }
        finally { Arrays.fill(owned.password(), '\0'); }
    }

    @Override public void discard() {
        Input<T> owned = input.getAndSet(null);
        if (owned != null) Arrays.fill(owned.password(), '\0');
    }
}
