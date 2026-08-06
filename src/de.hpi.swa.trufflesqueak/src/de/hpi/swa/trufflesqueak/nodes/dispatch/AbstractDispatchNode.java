/*
 * Copyright (c) 2025-2026 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2025-2026 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.dispatch;

import java.util.Arrays;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;

import de.hpi.swa.trufflesqueak.model.CompiledCodeObject;
import de.hpi.swa.trufflesqueak.model.NativeObject;
import de.hpi.swa.trufflesqueak.nodes.AbstractNode;
import de.hpi.swa.trufflesqueak.nodes.CacheLimits;
import de.hpi.swa.trufflesqueak.nodes.accessing.SqueakObjectClassNode;
import de.hpi.swa.trufflesqueak.nodes.accessing.SqueakObjectClassNodeGen;

public abstract class AbstractDispatchNode extends AbstractNode {
    protected final NativeObject selector;

    AbstractDispatchNode(final NativeObject selector) {
        this.selector = selector;
    }

    // --- Cache Manager and Data Nodes ---

    /**
     * This manager organizes dispatch caching into two distinct tiers to balance compilation size
     * and execution efficiency, using a parallel array architecture to preserve JIT profiling data.
     * <p>
     * 1. Fast Tier ({@code fastEntries}): Caches standard methods and unique fallback scenarios up to a
     * configured limit ({@code DISPATCH_CACHE_LIMIT}). Each entry maintains a flat array of class guards.
     * <br>
     * 2. Wide Tier ({@code wideEntries}): Handles class polymorphism. When a standard method exceeds its
     * allotted class guard limit ({@code LOOKUP_CACHE_LIMIT}) in the fast tier, it is promoted here.
     * <p>
     * <b>Unified Entry Architecture:</b><br>
     * To bypass Truffle's strict tree-parenting constraints during fast-to-wide promotion, the execution
     * nodes are wrapped in a generic {@code DispatchEntry}. When promoted, the entry drops its fast-tier
     * guard array to free memory and is directly moved from the {@code fastEntries} array to the
     * {@code wideEntries} array within the same parent node.
     * <p>
     * The manager is responsible for evaluating lookup results, transitioning nodes between tiers,
     * pruning invalidated cache entries, and signaling a transition to indirect execution when
     * the cache capacity is exhausted. Fallback mechanisms (e.g., #doesNotUnderstand) are strictly
     * isolated in the fast tier and are ineligible for wide tier promotion.
     *
     * @param <T> The type of direct dispatch node managed by this cache.
     */
    public static final class DispatchCacheManager<T extends AbstractDispatchDirectNode> extends Node {
        @Children public DispatchEntry<T>[] fastEntries;
        @Children public DispatchEntry<T>[] wideEntries;
        @Child public SqueakObjectClassNode classNode;

        @SuppressWarnings("unchecked")
        public DispatchCacheManager() {
            this.fastEntries = (DispatchEntry<T>[]) new DispatchEntry<?>[0];
            this.wideEntries = (DispatchEntry<T>[]) new DispatchEntry<?>[0];
            this.classNode = insert(SqueakObjectClassNodeGen.create());
        }

        @SuppressWarnings("unchecked")
        @TruffleBoundary
        protected T convertToIndirect() {
            // Safely drop the fast and wide tiers to free memory.
            this.fastEntries = insert((DispatchEntry<T>[]) new DispatchEntry<?>[0]);
            this.wideEntries = insert((DispatchEntry<T>[]) new DispatchEntry<?>[0]);
            this.classNode = null;
            return null;
        }

        @SuppressWarnings("unchecked")
        @TruffleBoundary
        protected T specialize(final Object receiver, final Object lookupResult, final T newDispatchNode) {
            final DispatchEntry<T>[] newFastEntries = (DispatchEntry<T>[]) new DispatchEntry<?>[CacheLimits.DISPATCH_CACHE_LIMIT];
            final DispatchEntry<T>[] newWideEntries = (DispatchEntry<T>[]) new DispatchEntry<?>[CacheLimits.DISPATCH_CACHE_LIMIT];

            int fastEntriesNeeded = 0;
            CompiledCodeObject targetMethodToWiden = null;
            DispatchEntry<T> targetEntry = null;

            // 1. Prune dead Fast entries and search for coalescing/promotion
            for (final DispatchEntry<T> current : fastEntries) {
                if (!current.isFastValid()) {
                    continue;
                }

                // Only coalesce standard methods. Fallbacks (null) and OAMs are isolated by class.
                if (targetEntry == null && lookupResult instanceof CompiledCodeObject targetMethod &&
                                current.methodOrNull == targetMethod &&
                                current.executor.getClass() == newDispatchNode.getClass()) {

                    // Method matches fast entry: append new guard or transition to wide, if needed.
                    if (current.appendFastGuard(receiver, newDispatchNode.getAssumptions())) {
                        newFastEntries[fastEntriesNeeded++] = current;
                        targetEntry = current;
                    } else {
                        targetMethodToWiden = targetMethod;
                        targetEntry = current;
                    }
                } else {
                    // Node survives unchanged
                    newFastEntries[fastEntriesNeeded++] = current;
                }
            }

            // 2. Prune dead Wide entries
            int wideEntriesNeeded = 0;
            for (final DispatchEntry<T> current : wideEntries) {
                if (current.isWideValid()) {
                    newWideEntries[wideEntriesNeeded++] = current;
                }
            }

            // 3. Handle Wide transition by mutating and moving the entry
            if (targetMethodToWiden != null) {
                targetEntry.promoteToWide();
                newWideEntries[wideEntriesNeeded++] = targetEntry;
                this.fastEntries = insert(Arrays.copyOf(newFastEntries, fastEntriesNeeded));
                this.wideEntries = insert(Arrays.copyOf(newWideEntries, wideEntriesNeeded));
                return targetEntry.executor;
            }

            // Make sure Wide entries are up to date (used by Step 4 and 5)
            if (wideEntriesNeeded != wideEntries.length) {
                this.wideEntries = insert(Arrays.copyOf(newWideEntries, wideEntriesNeeded));
            }

            // 4. Return existing appended executor, if found
            if (targetEntry != null) {
                // Update AST only if dead fast entries were pruned
                if (fastEntriesNeeded != fastEntries.length) {
                    this.fastEntries = insert(Arrays.copyOf(newFastEntries, fastEntriesNeeded));
                }
                return targetEntry.executor;
            }

            // 5. Append new Fast entry, if cache has space
            if (fastEntriesNeeded + wideEntriesNeeded < CacheLimits.DISPATCH_CACHE_LIMIT) {
                final DispatchEntry<T> newEntry = new DispatchEntry<>(receiver, lookupResult, newDispatchNode);
                newFastEntries[fastEntriesNeeded++] = newEntry;
                this.fastEntries = insert(Arrays.copyOf(newFastEntries, fastEntriesNeeded));
                return newDispatchNode;
            }

            // Capacity exhausted
            return convertToIndirect();
        }
    }

    public static final class DispatchEntry<T extends AbstractDispatchDirectNode> extends Node {
        public final CompiledCodeObject methodOrNull;
        @CompilationFinal(dimensions = 1) public final Assumption[] assumptions;

        @CompilationFinal(dimensions = 1) private GuardData[] fastGuards;
        @Child public T executor;

        public DispatchEntry(final Object receiver, final Object lookupResult, final T executor) {
            this.methodOrNull = lookupResult instanceof CompiledCodeObject m ? m : null;
            this.assumptions = executor.getAssumptions();
            this.fastGuards = new GuardData[]{new GuardData(receiver, this.assumptions)};
            this.executor = insert(executor);
        }

        @ExplodeLoop
        public boolean isFastCacheHit(final Object receiver) {
            final GuardData[] currentGuards = fastGuards;
            if (currentGuards == null) {
                return false;
            }

            for (int i = 0; i < currentGuards.length; i++) {
                final GuardData current = currentGuards[i];
                if (!Assumption.isValidAssumption(current.assumptions)) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    return removeInvalidAndCompleteCheck(receiver, i, currentGuards);
                } else if (current.guard.fastCheck(receiver)) {
                    return true;
                }
            }
            return false;
        }

        public boolean isWideCacheHit(final CompiledCodeObject targetMethod) {
            return methodOrNull == targetMethod && Assumption.isValidAssumption(assumptions);
        }

        boolean isFastValid() {
            return fastGuards != null && fastGuards.length > 0;
        }

        boolean isWideValid() {
            return Assumption.isValidAssumption(assumptions);
        }

        void promoteToWide() {
            this.fastGuards = null; // Drop fast-tier receiver checking to free memory
        }

        boolean appendFastGuard(final Object receiver, final Assumption[] newAssumptions) {
            final GuardData[] guards = fastGuards;
            if (guards == null) {
                return false;
            }

            // Determine how many guards are still valid
            int validCount = 0;
            for (final GuardData guard : guards) {
                if (Assumption.isValidAssumption(guard.assumptions)) {
                    validCount++;
                }
            }

            // Fail if the final count of guards exceed the limit
            if (validCount >= CacheLimits.LOOKUP_CACHE_LIMIT) {
                return false;
            }

            // Rebuild the array, pruning dead nodes and adding the new one in a single pass
            final GuardData[] newGuards = new GuardData[validCount + 1];
            int index = 0;
            for (final GuardData guard : guards) {
                if (Assumption.isValidAssumption(guard.assumptions)) {
                    newGuards[index++] = guard;
                }
            }

            newGuards[index] = new GuardData(receiver, newAssumptions);
            this.fastGuards = newGuards;
            return true;
        }

        @TruffleBoundary
        private boolean removeInvalidAndCompleteCheck(final Object receiver, final int firstInvalidIndex, final GuardData[] currentGuards) {
            // 0 through (firstInvalidIndex - 1) are valid. firstInvalidIndex is invalid.
            int validCount = firstInvalidIndex;
            for (int i = firstInvalidIndex + 1; i < currentGuards.length; i++) {
                if (Assumption.isValidAssumption(currentGuards[i].assumptions)) {
                    validCount++;
                }
            }

            final GuardData[] newGuards = new GuardData[validCount];

            // Copy the initial known valid entries.
            for (int i = 0; i < firstInvalidIndex; i++) {
                newGuards[i] = currentGuards[i];
            }

            boolean foundMatch = false;
            int newIndex = firstInvalidIndex;

            // Evaluate the tail for both validity and the receiver guard.
            for (int i = firstInvalidIndex + 1; i < currentGuards.length; i++) {
                final GuardData data = currentGuards[i];
                if (Assumption.isValidAssumption(data.assumptions)) {
                    newGuards[newIndex++] = data;

                    if (!foundMatch && data.guard.check(receiver)) {
                        foundMatch = true;
                    }
                }
            }

            this.fastGuards = newGuards;
            return foundMatch;
        }
    }

    private static final class GuardData {
        public final LookupClassGuard guard;
        @CompilationFinal(dimensions = 1) public final Assumption[] assumptions;

        public GuardData(final Object receiver, final Assumption[] assumptions) {
            this.guard = LookupClassGuard.create(receiver);
            this.assumptions = assumptions;
        }
    }

    @Override
    public final String toString() {
        return "send: " + selector.toString();
    }
}
