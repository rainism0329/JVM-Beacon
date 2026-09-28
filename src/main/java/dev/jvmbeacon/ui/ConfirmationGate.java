package dev.jvmbeacon.ui;

import java.util.function.Supplier;

/** EDT-only: modal dialogs pump timer events, so pause automatic requests during confirmation. */
final class ConfirmationGate {
    private int depth;

    boolean isPaused() { return depth > 0; }

    <T> T show(Supplier<T> dialog) {
        depth++;
        try { return dialog.get(); }
        finally { depth--; }
    }
}
