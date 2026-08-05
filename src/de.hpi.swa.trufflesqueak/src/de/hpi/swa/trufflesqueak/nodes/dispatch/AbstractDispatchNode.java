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

import de.hpi.swa.trufflesqueak.model.AbstractSqueakObjectWithClassAndHash;
import de.hpi.swa.trufflesqueak.model.ClassObject;
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
     * configured limit ({@code DISPATCH_CACHE_LIMIT}). Each entry maintains a chain of class guards.
     * <br>
     * 2. Wide Tier ({@code wideEntries}): Handles class polymorphism. When a standard method exceeds its
     * allotted class guard limit ({@code LOOKUP_CACHE_LIMIT}) in the fast tier, it is promoted here.
     * <p>
     * <b>Unified Entry Architecture:</b><br>
     * To bypass Truffle's strict tree-parenting constraints during fast-to-wide promotion, the execution
     * nodes are wrapped in a generic {@code DispatchEntry}. When promoted, the entry drops its fast-tier
     * AST guards to free memory and is directly moved from the {@code fastEntries} array to the
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

                if (targetEntry == null && lookupResult instanceof CompiledCodeObject targetMethod &&
                                current.methodOrNull == targetMethod &&
                                current.executor.getClass() == newDispatchNode.getClass()) {

                    // Method matches fast entry: append new guard or transition to wide, if needed.
                    if (current.guardChainNode.append(receiver, newDispatchNode.getAssumptions())) {
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

        @Child public GuardChainNode guardChainNode;
        @Child public T executor;

        public DispatchEntry(final Object receiver, final Object lookupResult, final T executor) {
            this.methodOrNull = lookupResult instanceof CompiledCodeObject m ? m : null;
            this.assumptions = executor.getAssumptions();
            this.guardChainNode = insert(new GuardChainNode(receiver, this.assumptions));
            this.executor = insert(executor);
        }

        public boolean isFastCacheHit(final Object receiver) {
            // Safely deflect lagging compiled frames that hit this entry after it was promoted to Wide
            final GuardChainNode chain = this.guardChainNode;
            if (chain == null) {
                return false;
            }
            return chain.execute(receiver);
        }

        public boolean isWideCacheHit(final CompiledCodeObject targetMethod) {
            return methodOrNull == targetMethod && Assumption.isValidAssumption(assumptions);
        }

        public boolean isFastValid() {
            final GuardChainNode chain = guardChainNode;
            return chain != null && !chain.isEmpty();
        }

        public boolean isWideValid() {
            return Assumption.isValidAssumption(assumptions);
        }

        public void promoteToWide() {
            this.guardChainNode = null; // Drop fast-tier receiver checking to free memory
        }
    }

    public static final class GuardChainNode extends AbstractNode {
        @Children private ClassGuardDataNode[] classGuards;
        @Children private GenericGuardDataNode[] genericGuards;

        public GuardChainNode(final Object receiver, final Assumption[] assumptions) {
            if (receiver instanceof final AbstractSqueakObjectWithClassAndHash squeakObj) {
                // ToDo: not sure this is needed -- included to parallel LookupClassGuard implementation
                final AbstractSqueakObjectWithClassAndHash resolved = (AbstractSqueakObjectWithClassAndHash) squeakObj.resolveForwardingPointer();
                this.classGuards = insert(new ClassGuardDataNode[]{new ClassGuardDataNode(resolved.getSqueakClass(), assumptions)});
                this.genericGuards = insert(new GenericGuardDataNode[0]);
            } else {
                this.classGuards = insert(new ClassGuardDataNode[0]);
                this.genericGuards = insert(new GenericGuardDataNode[]{new GenericGuardDataNode(receiver, assumptions)});
            }
        }

        public boolean isEmpty() {
            return classGuards.length == 0 && genericGuards.length == 0;
        }

        @ExplodeLoop
        public boolean execute(final Object receiver) {
            // FAST PATH FOR SQUEAK OBJECTS
            // Evaluated if there are class guards and the receiver is a Squeak object.
            if (classGuards.length > 0 && receiver instanceof final AbstractSqueakObjectWithClassAndHash squeakObj) {
                final ClassObject actualClass = squeakObj.getSqueakClass();
                final ClassGuardDataNode[] currentClass = classGuards;
                for (int i = 0; i < currentClass.length; i++) {
                    final ClassGuardDataNode current = currentClass[i];
                    if (!Assumption.isValidAssumption(current.assumptions)) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        return removeInvalidAndCompleteCheck(receiver);
                    } else if (current.expectedClass == actualClass) {
                        return true;
                    }
                }
            }

            // FAST PATH FOR PRIMITIVES / SINGLETONS / ALL OTHERS
            // Evaluated if there are generic guards (and receiver fell through the Squeak check).
            if (genericGuards.length > 0) {
                final GenericGuardDataNode[] currentGeneric = genericGuards;
                for (int i = 0; i < currentGeneric.length; i++) {
                    final GenericGuardDataNode current = currentGeneric[i];
                    if (!Assumption.isValidAssumption(current.assumptions)) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        return removeInvalidAndCompleteCheck(receiver);
                    } else if (current.guard.check(receiver)) {
                        return true;
                    }
                }
            }

            return false;
        }

        public boolean append(final Object receiver, final Assumption[] assumptions) {
            int totalSize = classGuards.length + genericGuards.length;

            // Lazy Pruning: Only prune the opposite list if we are out of space.
            if (totalSize >= CacheLimits.LOOKUP_CACHE_LIMIT) {
                fullPrune();
                totalSize = classGuards.length + genericGuards.length;
                if (totalSize >= CacheLimits.LOOKUP_CACHE_LIMIT) {
                    return false; // Still full after prune, trigger Wide tier promotion.
                }
            }

            if (receiver instanceof AbstractSqueakObjectWithClassAndHash squeakObj) {
                // ToDo: not sure this is needed -- included to parallel LookupClassGuard implementation
                final AbstractSqueakObjectWithClassAndHash resolved = (AbstractSqueakObjectWithClassAndHash) squeakObj.resolveForwardingPointer();
                final ClassObject expectedClass = resolved.getSqueakClass();
                final ClassGuardDataNode[] newGuards = new ClassGuardDataNode[getValidCount(classGuards) + 1];
                int index = 0;
                for (final ClassGuardDataNode guard : classGuards) {
                    if (Assumption.isValidAssumption(guard.assumptions)) {
                        newGuards[index++] = guard;
                    }
                }
                newGuards[index] = new ClassGuardDataNode(expectedClass, assumptions);
                this.classGuards = insert(newGuards);
            } else {
                final GenericGuardDataNode[] newGuards = new GenericGuardDataNode[getValidCount(genericGuards) + 1];
                int index = 0;
                for (final GenericGuardDataNode guard : genericGuards) {
                    if (Assumption.isValidAssumption(guard.assumptions)) {
                        newGuards[index++] = guard;
                    }
                }
                newGuards[index] = new GenericGuardDataNode(receiver, assumptions);
                this.genericGuards = insert(newGuards);
            }
            return true;
        }

        private static int getValidCount(final ClassGuardDataNode[] guards) {
            int validCount = 0;
            for (final ClassGuardDataNode guard : guards) {
                if (Assumption.isValidAssumption(guard.assumptions)) validCount++;
            }
            return validCount;
        }

        private static int getValidCount(final GenericGuardDataNode[] guards) {
            int validCount = 0;
            for (final GenericGuardDataNode guard : guards) {
                if (Assumption.isValidAssumption(guard.assumptions)) validCount++;
            }
            return validCount;
        }

        @TruffleBoundary
        private void fullPrune() {
            final int validClass = getValidCount(classGuards);
            if (validClass != classGuards.length) {
                final ClassGuardDataNode[] newClass = new ClassGuardDataNode[validClass];
                int index = 0;
                for (final ClassGuardDataNode guard : classGuards) {
                    if (Assumption.isValidAssumption(guard.assumptions)) newClass[index++] = guard;
                }
                this.classGuards = insert(newClass);
            }

            final int validGeneric = getValidCount(genericGuards);
            if (validGeneric != genericGuards.length) {
                final GenericGuardDataNode[] newGeneric = new GenericGuardDataNode[validGeneric];
                int index = 0;
                for (final GenericGuardDataNode guard : genericGuards) {
                    if (Assumption.isValidAssumption(guard.assumptions)) newGeneric[index++] = guard;
                }
                this.genericGuards = insert(newGeneric);
            }
        }

        @TruffleBoundary
        private boolean removeInvalidAndCompleteCheck(final Object receiver) {
            fullPrune();

            if (receiver instanceof final AbstractSqueakObjectWithClassAndHash squeakObj) {
                final ClassObject actualClass = squeakObj.getSqueakClass();
                for (final ClassGuardDataNode current : classGuards) {
                    if (current.expectedClass == actualClass) {
                        return true;
                    }
                }
            } else {
                for (final GenericGuardDataNode current : genericGuards) {
                    if (current.guard.check(receiver)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public static final class ClassGuardDataNode extends Node {
        public final ClassObject expectedClass;
        @CompilationFinal(dimensions = 1) public final Assumption[] assumptions;

        public ClassGuardDataNode(final ClassObject expectedClass, final Assumption[] assumptions) {
            this.expectedClass = expectedClass;
            this.assumptions = assumptions;
        }
    }

    public static final class GenericGuardDataNode extends Node {
        public final LookupClassGuard guard;
        @CompilationFinal(dimensions = 1) public final Assumption[] assumptions;

        public GenericGuardDataNode(final Object receiver, final Assumption[] assumptions) {
            this.guard = LookupClassGuard.create(receiver);
            this.assumptions = assumptions;
        }
    }

    @Override
    public final String toString() {
        return "send: " + selector.toString();
    }
}
