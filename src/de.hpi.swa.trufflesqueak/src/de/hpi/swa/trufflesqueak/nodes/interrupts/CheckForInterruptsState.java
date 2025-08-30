/*
 * Copyright (c) 2017-2025 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021-2025 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.interrupts;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import de.hpi.swa.trufflesqueak.image.SqueakImageContext;
import de.hpi.swa.trufflesqueak.util.LogUtils;
import de.hpi.swa.trufflesqueak.util.MiscUtils;

public final class CheckForInterruptsState {
    private static final String CHECK_FOR_INTERRUPTS_THREAD_NAME = "TruffleSqueakCheckForInterrupts";

    private static final int DEFAULT_INTERRUPT_CHECK_NANOS = 2_000_000;

    private final SqueakImageContext image;
    private final ArrayDeque<Integer> semaphoresToSignal = new ArrayDeque<>();

    /**
     * `interruptCheckNanos` is the interval between updates to 'shouldTrigger'. This controls the
     * timing accuracy of Smalltalk Delays.
     */
    private long interruptCheckNanos = DEFAULT_INTERRUPT_CHECK_NANOS;

    // Main thread controls this flag to enable/disable interrupt signals.
    private final AtomicBoolean interruptEnabled = new AtomicBoolean(true);

    // This volatile flag is for the fast, initial check by the main thread.
    // It is set only when interrupts are enabled.
    private volatile boolean interruptPending = false;

    // This AtomicBoolean is always set on an interrupt, regardless of `interruptEnabled`.
    // It serves as the definitive, atomic record of an interrupt occurring.
    private final AtomicBoolean interruptRequest = new AtomicBoolean(false);

    private volatile boolean userInterruptPending;
    private long nextWakeupTick;
    private boolean hasPendingFinalizations;

    private Thread thread;

    public CheckForInterruptsState(final SqueakImageContext image) {
        this.image = image;
        if (image.options.disableInterruptHandler()) {
            LogUtils.INTERRUPTS.info("Interrupt handler disabled...");
        }
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
                // Check for wake up tick trigger (class Delay) only.
                if (nextWakeUpTickTrigger()) {
                    signalInterrupt();
                }
                LockSupport.parkNanos(interruptCheckNanos);
                // Handle thread interrupts
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
        // Fast, initial check using the volatile flag.
        if (interruptPending) {
            return shouldSkipSlow();
        }
        return true;
    }

    @TruffleBoundary
    private boolean shouldSkipSlow() {
        // Skip if interrupts are disabled.
        interruptPending = false;
        if (!interruptEnabled.get()) {
            return true;
        }

        // Perform the definitive atomic check and clear.
       return !interruptRequest.getAndSet(false);
    }

    /* Record that some type interrupt has occurred. Can be called by other threads */
    public void signalInterrupt() {
        // Always set the definitive interruptRequest flag.
        interruptRequest.set(true);

        // Only set the fast interruptPending flag if interrupts are enabled.
        if (interruptEnabled.get()) {
            interruptPending = true;
        }
    }

    /* Enable / disable interrupts */

    public boolean isActive() {
        return interruptEnabled.get();
    }

    public void activate() {
        interruptEnabled.set(true);
        interruptPending = interruptRequest.get();
    }

    public void deactivate() {
        interruptEnabled.set(false);
    }

    /* User interrupt */

    public boolean tryInterruptPending() {
        if (userInterruptPending) {
            LogUtils.INTERRUPTS.fine("User interrupt");
            userInterruptPending = false; // reset
            return true;
        } else {
            return false;
        }
    }

    public void setInterruptPending() {
        userInterruptPending = true;
        signalInterrupt();
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
        signalInterrupt();
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
        signalInterrupt();
    }

    /*
     * TESTING
     */

    public void clear() {
        userInterruptPending = false;
        nextWakeupTick = 0;
        hasPendingFinalizations = false;
        clearWeakPointersQueue();
        semaphoresToSignal.clear();
    }

    public void reset() {
        CompilerAsserts.neverPartOfCompilation("Resetting interrupt handler only supported for testing purposes");
        activate();
        shutdown();
        clear();
    }

    private void clearWeakPointersQueue() {
        while (image.weakPointersQueue.poll() != null) {
            // Poll until empty.
        }
    }
}
