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
     * This manager organizes dispatch nodes into two distinct tiers to balance compilation size
     * and execution efficiency:
     * 1. Fast Tier (fastNodes): Caches standard methods and unique fallback scenarios up to a
     * configured limit (DISPATCH_CACHE_SIZE). Each entry maintains a chain of class guards.
     * 2. Wide Tier (wideNodes): Handles class polymorphism. When a standard method exceeds its
     * allotted class guard limit (LOOKUP_CACHE_SIZE) in the fast tier, it is promoted here.
     * <p>
     * The manager is responsible for evaluating lookup results, transitioning nodes between tiers,
     * pruning invalidated cache entries, and signaling a transition to indirect execution when
     * the cache capacity is exhausted. Fallback mechanisms (e.g., #doesNotUnderstand) are strictly
     * isolated in the fast tier and are ineligible for wide tier promotion.
     *
     * @param <T> The type of direct dispatch node managed by this cache.
     */
    public static final class DispatchCacheManager<T extends AbstractDispatchDirectNode> extends Node {
        @Children public FastDispatchDataNode<T>[] fastNodes;
        @Children public WideDispatchDataNode<T>[] wideNodes;
        @Child public SqueakObjectClassNode classNode;

        @SuppressWarnings("unchecked")
        public DispatchCacheManager() {
            this.fastNodes = (FastDispatchDataNode<T>[]) new FastDispatchDataNode<?>[0];
            this.wideNodes = (WideDispatchDataNode<T>[]) new WideDispatchDataNode<?>[0];
            this.classNode = insert(SqueakObjectClassNodeGen.create());
        }

        @SuppressWarnings("unchecked")
        @TruffleBoundary
        protected T convertToIndirect() {
            // Safely drop the fast and wide tiers to free memory.
            this.fastNodes = insert((FastDispatchDataNode<T>[]) new FastDispatchDataNode<?>[0]);
            this.wideNodes = insert((WideDispatchDataNode<T>[]) new WideDispatchDataNode<?>[0]);
            this.classNode = null;
            return null;
        }

        @SuppressWarnings("unchecked")
        @TruffleBoundary
        protected T specialize(final Object receiver, final Object lookupResult, final T newDispatchNode) {
            final FastDispatchDataNode<T>[] newFastNodes = (FastDispatchDataNode<T>[]) new FastDispatchDataNode<?>[CacheLimits.DISPATCH_CACHE_LIMIT];
            int fastNodesNeeded = 0;

            T resultNode = null;
            CompiledCodeObject targetMethodToWiden = null;

            // 1. Prune dead nodes and search for coalescing/promotion opportunities
            for (final FastDispatchDataNode<T> currentFast : fastNodes) {
                if (currentFast.guardChainNode.isEmpty()) {
                    continue;
                }

                // Only coalesce standard methods. Fallbacks (null) and OAMs are isolated by class.
                if (resultNode == null && lookupResult instanceof CompiledCodeObject targetMethod &&
                                currentFast.standardMethodOrNull == targetMethod &&
                                currentFast.dispatchDirectNode.getClass() == newDispatchNode.getClass()) {

                    // Method matches fast entry: append new guard or transition to wide, if needed.
                    if (currentFast.guardChainNode.append(receiver, newDispatchNode.getAssumptions())) {
                        newFastNodes[fastNodesNeeded++] = currentFast;
                        resultNode = currentFast.dispatchDirectNode;
                    } else {
                        // Trigger wide transition
                        targetMethodToWiden = targetMethod;
                        resultNode = currentFast.dispatchDirectNode;
                    }
                } else {
                    // Node survives unchanged
                    newFastNodes[fastNodesNeeded++] = currentFast;
                }
            }

            // 2. Handle Wide transition
            if (targetMethodToWiden != null) {
                final WideDispatchDataNode<T> newWide = new WideDispatchDataNode<>(targetMethodToWiden, newDispatchNode);
                appendWideNode(newWide);
                this.fastNodes = insert(Arrays.copyOf(newFastNodes, fastNodesNeeded));
                return newWide.dispatchDirectNode;
            }

            // 3. Return existing appended node, if found
            if (resultNode != null) {
                // Update AST only if dead nodes were pruned
                if (fastNodesNeeded != fastNodes.length) {
                    this.fastNodes = insert(Arrays.copyOf(newFastNodes, fastNodesNeeded));
                }
                return resultNode;
            }

            // 4. Global budget check & append new Fast node, if possible
            if (fastNodesNeeded + wideNodes.length < CacheLimits.DISPATCH_CACHE_LIMIT) {
                final FastDispatchDataNode<T> newFast = new FastDispatchDataNode<>(receiver, lookupResult, newDispatchNode);
                newFastNodes[fastNodesNeeded++] = newFast;
                this.fastNodes = insert(Arrays.copyOf(newFastNodes, fastNodesNeeded));
                return newFast.dispatchDirectNode;
            }

            // Capacity exhausted, transition to indirect execution.
            return convertToIndirect();
        }

        @TruffleBoundary
        private void appendWideNode(final WideDispatchDataNode<T> node) {
            final WideDispatchDataNode<T>[] newArray = Arrays.copyOf(wideNodes, wideNodes.length + 1);
            newArray[wideNodes.length] = node;
            this.wideNodes = insert(newArray);
        }
    }

    public static final class FastDispatchDataNode<T extends AbstractDispatchDirectNode> extends Node {
        public final CompiledCodeObject standardMethodOrNull;
        @Child public GuardChainNode guardChainNode;
        @Child public T dispatchDirectNode;

        public FastDispatchDataNode(final Object receiver, final Object lookupResult, final T dispatchNode) {
            this.guardChainNode = insert(new GuardChainNode(receiver, dispatchNode.getAssumptions()));
            this.standardMethodOrNull = lookupResult instanceof CompiledCodeObject m ? m : null;
            this.dispatchDirectNode = insert(dispatchNode);
        }
    }

    public static final class WideDispatchDataNode<T extends AbstractDispatchDirectNode> extends Node {
        public final CompiledCodeObject standardMethod;
        @Child public T dispatchDirectNode;

        public WideDispatchDataNode(final CompiledCodeObject method, final T dispatchNode) {
            assert method != null : "Fallbacks must not enter the wide cache tier";
            this.standardMethod = method;
            this.dispatchDirectNode = insert(dispatchNode);
        }
    }

    public abstract static class AbstractGuardNode extends AbstractNode {
        public abstract boolean execute(Object receiver);

        public abstract boolean append(Object receiver, Assumption[] assumptions);
    }

    public static final class GuardChainNode extends AbstractGuardNode {
        @Children private GuardChainDataNode[] guards;

        public GuardChainNode(final Object receiver, final Assumption[] assumptions) {
            this.guards = insert(new GuardChainDataNode[]{new GuardChainDataNode(receiver, assumptions)});
        }

        public boolean isEmpty() {
            return guards.length == 0;
        }

        @Override
        @ExplodeLoop
        public boolean execute(final Object receiver) {
            final GuardChainDataNode[] currentGuards = this.guards;
            for (int i = 0; i < currentGuards.length; i++) {
                final GuardChainDataNode current = currentGuards[i];
                if (!Assumption.isValidAssumption(current.assumptions)) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    return removeInvalidAndCompleteCheck(receiver, i, currentGuards);
                } else if (current.guard.check(receiver)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean append(final Object receiver, final Assumption[] assumptions) {
            // Determine how many guards are still valid
            int validCount = 0;
            for (final GuardChainDataNode guard : guards) {
                if (Assumption.isValidAssumption(guard.assumptions)) {
                    validCount++;
                }
            }

            // Fail if the final count of guards exceed the limit
            if (validCount >= CacheLimits.LOOKUP_CACHE_LIMIT) {
                return false;
            }

            CompilerDirectives.transferToInterpreterAndInvalidate();

            // Rebuild the array, pruning dead nodes and adding the new one in a single pass
            final GuardChainDataNode[] newGuards = new GuardChainDataNode[validCount + 1];
            int index = 0;
            for (final GuardChainDataNode guard : guards) {
                if (Assumption.isValidAssumption(guard.assumptions)) {
                    newGuards[index++] = guard;
                }
            }

            newGuards[index] = new GuardChainDataNode(receiver, assumptions);
            this.guards = insert(newGuards);
            return true;
        }

        @TruffleBoundary
        private boolean removeInvalidAndCompleteCheck(final Object receiver, final int firstInvalidIndex, final GuardChainDataNode[] currentGuards) {
            // 0 through (firstInvalidIndex - 1) are valid.
            int validCount = firstInvalidIndex;
            for (int i = firstInvalidIndex + 1; i < currentGuards.length; i++) {
                if (Assumption.isValidAssumption(currentGuards[i].assumptions)) {
                    validCount++;
                }
            }

            final GuardChainDataNode[] newGuards = new GuardChainDataNode[validCount];

            // Copy the initial known valid entries.
            for (int i = 0; i < firstInvalidIndex; i++) {
                newGuards[i] = currentGuards[i];
            }

            boolean foundMatch = false;
            int newIndex = firstInvalidIndex;

            // Evaluate the tail for both validity and the receiver guard.
            for (int i = firstInvalidIndex + 1; i < currentGuards.length; i++) {
                final GuardChainDataNode node = currentGuards[i];
                if (Assumption.isValidAssumption(node.assumptions)) {
                    newGuards[newIndex++] = node;

                    if (!foundMatch && node.guard.check(receiver)) {
                        foundMatch = true;
                    }
                }
            }

            this.guards = insert(newGuards);
            return foundMatch;
        }
    }

    public static final class GuardChainDataNode extends Node {
        public final LookupClassGuard guard;
        @CompilationFinal(dimensions = 1) public final Assumption[] assumptions;

        public GuardChainDataNode(final Object receiver, final Assumption[] assumptions) {
            this.guard = LookupClassGuard.create(receiver);
            this.assumptions = assumptions;
        }
    }

    @Override
    public final String toString() {
        return "send: " + selector.toString();
    }
}
