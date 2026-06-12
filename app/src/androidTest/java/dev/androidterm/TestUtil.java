package dev.androidterm;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Polling helper: shell output is asynchronous, fixed sleeps are flaky. */
public final class TestUtil {
    private TestUtil() {}

    public static void waitFor(String what, long timeoutMs, BooleanSupplier condition) {
        waitFor(what, timeoutMs, condition, () -> "");
    }

    /** diagnostic is evaluated on timeout and appended to the failure. */
    public static void waitFor(String what, long timeoutMs, BooleanSupplier condition,
            Supplier<String> diagnostic) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new AssertionError("interrupted", e);
            }
        }
        throw new AssertionError(
                "timed out waiting for: " + what + "\n" + diagnostic.get());
    }
}
