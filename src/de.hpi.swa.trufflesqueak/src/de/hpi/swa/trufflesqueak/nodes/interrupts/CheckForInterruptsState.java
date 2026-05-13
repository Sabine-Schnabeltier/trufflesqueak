/*
 * Copyright (c) 2017-2026 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021-2026 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.interrupts;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.LockSupport;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import de.hpi.swa.trufflesqueak.image.SqueakImageContext;
import de.hpi.swa.trufflesqueak.util.DebugUtils;
import de.hpi.swa.trufflesqueak.util.LogUtils;
import de.hpi.swa.trufflesqueak.util.MiscUtils;

public final class CheckForInterruptsState {
    private static final String CHECK_FOR_INTERRUPTS_THREAD_NAME = "TruffleSqueakCheckForInterrupts";

    private static final int DEFAULT_INTERRUPT_CHECK_NANOS = 2_000_000;

    /**
     * Support for safely accessing the `shouldTrigger` flag across threads. We use a VarHandle with
     * opaque access (rather than a standard `volatile` boolean) to guarantee memory visibility
     * between the background interrupt thread and the main interpreter thread. This prevents the
     * Graal compiler from improperly loop-hoisting the read during JIT compilation, while
     * explicitly avoiding the performance penalty of full hardware memory barriers on weakly
     * ordered architectures like ARM64.
     */
    private static final VarHandle SHOULD_TRIGGER;
    static {
        try {
            SHOULD_TRIGGER = MethodHandles.lookup().findVarHandle(CheckForInterruptsState.class, "shouldTrigger", boolean.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw CompilerDirectives.shouldNotReachHere("Unable to find a VarHandle for shouldTrigger", e);
        }
    }

    private final SqueakImageContext image;
    private final ConcurrentLinkedDeque<Integer> semaphoresToSignal = new ConcurrentLinkedDeque<>();

    /**
     * `interruptCheckNanos` is the interval between updates to 'shouldTrigger'. This controls the
     * timing accuracy of Smalltalk Delays.
     */
    private long interruptCheckNanos = DEFAULT_INTERRUPT_CHECK_NANOS;

    private boolean isActive = true;
    private volatile long nextWakeupTick;
    private volatile boolean interruptPending;
    private volatile boolean hasPendingFinalizations;
    @SuppressWarnings("unused") private boolean shouldTrigger;

    private Thread thread;

    public CheckForInterruptsState(final SqueakImageContext image) {
        this.image = image;
        if (image.options.disableInterruptHandler()) {
            LogUtils.INTERRUPTS.info("Interrupt handler disabled...");
        }

        final Thread watchdog = new Thread(() -> {
            try {
                // Sleep for 14 minutes (14 * 60 * 1000 ms)
                Thread.sleep(14L * 60L * 1000L);

                printlnErr("\n\n=======================================================");
                printlnErr("[!!!] WATCHDOG TIMEOUT TRIGGERED: 14 MINUTES REACHED [!!!]");
                printlnErr("=======================================================\n");

                // 1. Try to dump Squeak state using the explicit image context!
                try {
                    printlnErr("Attempting Squeak-level state dump...");

                    // Temporarily attach this background thread to the Truffle Context.
                    // This makes SqueakImageContext.getSlow() work inside .toString() methods!
                    Object prevTruffleContext = null;
                    try {
                        prevTruffleContext = image.env.getContext().enter(null);
                    } catch (Throwable t) {
                        printlnErr("-> Warning: Could not bind Truffle Context.");
                    }

                    try {
                        DebugUtils.dumpState(image);
                    } finally {
                        // Always clean up and detach the thread when done
                        if (prevTruffleContext != null) {
                            image.env.getContext().leave(null, prevTruffleContext);
                        }
                    }

                } catch (Throwable t) {
                    printlnErr("-> Squeak-level dump failed: " + t.toString());
                    t.printStackTrace();
                }

                printlnErr("\nFORCING FULL JVM THREAD DUMP AND EXITING...");
                printlnErr("=======================================================\n");

                // 2. Dump raw JVM threads
                final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
                final ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);

                for (ThreadInfo info : threadInfos) {
                    printErr(info.toString());
                }

                printlnErr("\n=======================================================");
                printlnErr("END OF THREAD DUMP. KILLING PROCESS.");
                printlnErr("=======================================================\n");

                // 3. Hard exit to fail the CI step immediately
                System.exit(1);

            } catch (InterruptedException e) {
                // The VM is shutting down cleanly before 25 minutes, let the watchdog die
            }
        }, "TruffleSqueak-Watchdog");

        // Daemon ensures it won't keep the JVM alive if tests finish early
        watchdog.setDaemon(true);
        watchdog.start();

    }

    @TruffleBoundary
    public void start() {
        if (image.options.disableInterruptHandler()) {
            return;
        }
        thread = new CheckForInterruptsThread();
        thread.start();
    }

    final class CheckForInterruptsThread extends Thread {
        CheckForInterruptsThread() {
            super(CHECK_FOR_INTERRUPTS_THREAD_NAME);
            setDaemon(true);
        }

        @Override
        public void run() {
            while (true) {
                /*
                 * Check for wake up interrupts; all other interrupt sources signal immediately.
                 */
                if (nextWakeUpTickTrigger()) {
                    SHOULD_TRIGGER.setOpaque(CheckForInterruptsState.this, true);
                }
                LockSupport.parkNanos(interruptCheckNanos);

                if (Thread.interrupted()) {
                    break;
                }
            }
        }
    }

    @TruffleBoundary
    public void shutdown() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    /* Interrupt check interval */

    public long getInterruptCheckMilliseconds() {
        return interruptCheckNanos / 1_000_000;
    }

    public void setInterruptCheckMilliseconds(final long milliseconds) {
        interruptCheckNanos = Math.max(DEFAULT_INTERRUPT_CHECK_NANOS, milliseconds * 1_000_000);
    }

    /* Interrupt trigger state */

    public boolean shouldSkip() {
        if (!isActive) {
            return true;
        }
        if ((boolean) SHOULD_TRIGGER.getOpaque(this)) {
            SHOULD_TRIGGER.setOpaque(this, false);
            return false;
        } else {
            return true;
        }
    }

    /* Enable / disable interrupts */

    public boolean deactivate() {
        final boolean wasActive = isActive;
        isActive = false;
        return wasActive;
    }

    public void reactivate(final boolean wasActive) {
        isActive = wasActive;
    }

    /* User interrupt */

    public boolean tryInterruptPending() {
        if (interruptPending) {
            LogUtils.INTERRUPTS.fine("User interrupt");
            interruptPending = false; // reset
            return true;
        } else {
            return false;
        }
    }

    public void setInterruptPending() {
        interruptPending = true;
        SHOULD_TRIGGER.setOpaque(this, true);
    }

    /* Timer interrupt */

    private boolean nextWakeUpTickTrigger() {
        if (nextWakeupTick != 0) {
            final long time = MiscUtils.currentTimeMillis();
            if (time >= nextWakeupTick) {
                LogUtils.INTERRUPTS.finer(() -> "Reached nextWakeupTick: " + nextWakeupTick);
                return true;
            }
        }
        return false;
    }

    public boolean tryWakeUpTickTrigger() {
        if (nextWakeUpTickTrigger()) {
            LogUtils.INTERRUPTS.fine("Timer interrupt");
            nextWakeupTick = 0; // reset
            return true;
        } else {
            return false;
        }
    }

    public void setNextWakeupTick(final long msTime) {
        LogUtils.INTERRUPTS.finer(() -> {
            if (nextWakeupTick != 0) {
                return (msTime != 0 ? "Changing nextWakeupTick to " + msTime + " from " : "Resetting nextWakeupTick from ") + nextWakeupTick;
            } else {
                return msTime != 0 ? "Setting nextWakeupTick to " + msTime : "Resetting nextWakeupTick when it was already 0";
            }
        });
        nextWakeupTick = msTime;
    }

    /* Finalization interrupt */

    public boolean tryPendingFinalizations() {
        if (hasPendingFinalizations) {
            LogUtils.INTERRUPTS.fine("Finalization interrupt");
            hasPendingFinalizations = false;
            return true;
        } else {
            return false;
        }
    }

    public void setPendingFinalizations() {
        hasPendingFinalizations = true;
        SHOULD_TRIGGER.setOpaque(this, true);
    }

    /* Semaphore interrupts */

    private boolean hasSemaphoresToSignal() {
        return !semaphoresToSignal.isEmpty();
    }

    public boolean trySemaphoresToSignal() {
        if (hasSemaphoresToSignal()) {
            LogUtils.INTERRUPTS.fine("Semaphore interrupt");
            return true;
        } else {
            return false;
        }
    }

    public Integer nextSemaphoreToSignal() {
        return semaphoresToSignal.pollFirst();
    }

    @TruffleBoundary
    public void signalSemaphoreWithIndex(final int index) {
        semaphoresToSignal.addLast(index);
        SHOULD_TRIGGER.setOpaque(this, true);
    }

    /*
     * TESTING
     */

    public void clear() {
        nextWakeupTick = 0;
        interruptPending = false;
        hasPendingFinalizations = false;
        clearWeakPointersQueue();
        semaphoresToSignal.clear();
    }

    public void reset() {
        CompilerAsserts.neverPartOfCompilation("Resetting interrupt handler only supported for testing purposes");
        isActive = true;
        shutdown();
        clear();
    }

    private void clearWeakPointersQueue() {
        while (image.weakPointersQueue.poll() != null) {
            // Poll until empty.
        }
    }

    private static void printErr(final String message) {
        // Checkstyle: stop
        System.err.print(message);
        // Checkstyle: resume
    }

    private static void printlnErr(final String message) {
        // Checkstyle: stop
        System.err.println(message);
        // Checkstyle: resume
    }
}
