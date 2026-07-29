/*
 * Copyright (c) 2025-2026 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2025-2026 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.dispatch;

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

import java.util.concurrent.atomic.LongAdder;

public abstract class AbstractDispatchNode extends AbstractNode {
    protected final NativeObject selector;

    AbstractDispatchNode(final NativeObject selector) {
        this.selector = selector;
    }

    public static final boolean ENABLE_STATS = true;
    public static final LongAdder[][] DISPATCH_HISTOGRAM = init2DHistogram(CacheLimits.DISPATCH_CACHE_LIMIT + 1);
    public static final LongAdder INDIRECT_COUNT = new LongAdder();

    private static LongAdder[][] init2DHistogram(final int cacheLimit) {
        if (!ENABLE_STATS) {
            return null;
        }
        final LongAdder[][] matrix = new LongAdder[cacheLimit][cacheLimit];
        for (int f = 0; f < cacheLimit; f++) {
            for (int w = 0; w < cacheLimit; w++) {
                matrix[f][w] = new LongAdder();
            }
        }
        return matrix;
    }

    static {
        if (ENABLE_STATS) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n=== Dispatch Cache Statistics ===");
                System.out.println("Fast/Wide Tier Distribution (non-zero):");

                for (int f = 0; f <= CacheLimits.DISPATCH_CACHE_LIMIT; f++) {
                    for (int w = 0; w <= CacheLimits.DISPATCH_CACHE_LIMIT; w++) {
                        long count = DISPATCH_HISTOGRAM[f][w].sum();
                        if (count > 0) {
                            System.out.printf("  [%d fast, %d wide]: %d nodes%n", f, w, count);
                        }
                    }
                }

                System.out.println("Indirect Nodes: " + INDIRECT_COUNT.sum());
                System.out.println("=================================\n");
            }, "Dispatch-Stats-Hook"));
        }
    }

    // --- Cache Manager and Data Nodes ---

    /**
     * This manager organizes dispatch nodes into two distinct tiers to balance compilation size
     * and execution efficiency:
     * 1. Fast Tier (headFast): Caches standard methods and unique fallback scenarios up to a
     * configured limit (DISPATCH_CACHE_SIZE). Each entry maintains a chain of class guards.
     * 2. Wide Tier (headWide): Handles class polymorphism. When a standard method exceeds its
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
        @Child public FastDispatchDataNode<T> headFast;
        @Child public WideDispatchDataNode<T> headWide;

        public DispatchCacheManager() {
            if (ENABLE_STATS) {
                DISPATCH_HISTOGRAM[0][0].increment();
            }
        }

        @TruffleBoundary
        protected T convertToIndirect() {
            if (ENABLE_STATS) {
                DISPATCH_HISTOGRAM[countFastNodes()][countWideNodes()].decrement();
                INDIRECT_COUNT.increment();
            }

            // Safely drop the fast and wide tiers to free memory.
            this.headFast = null;
            this.headWide = null;
            return null;
        }

        @TruffleBoundary
        protected T specialize(final Object receiver, final Object lookupResult, final T newDispatchNode) {
            int oldFast = 0;
            int oldWide = 0;

            if (ENABLE_STATS) {
                oldFast = countFastNodes();
                oldWide = countWideNodes();
            }

            try {
                int totalMethodCount = 0;

                FastDispatchDataNode<T> currentFast = headFast;
                FastDispatchDataNode<T> previousFast = null;

                // 1. Scan Fast Chain to append guard or transition to Wide
                while (currentFast != null) {
                    // Prune nodes with invalidated guards.
                    if (currentFast.guardChainNode.head == null) {
                        removeFastNode(currentFast, previousFast);
                        currentFast = previousFast == null ? headFast : previousFast.next;
                        continue;
                    }

                    totalMethodCount++;

                    // Only coalesce standard methods. Fallbacks (null) and OAMs are isolated by class.
                    if (lookupResult instanceof CompiledCodeObject targetMethod &&
                            currentFast.standardMethodOrNull == targetMethod &&
                            currentFast.dispatchDirectNode.getClass() == newDispatchNode.getClass()) {

                        if (currentFast.guardChainNode.append(receiver, newDispatchNode.getAssumptions())) {
                            return currentFast.dispatchDirectNode;
                        } else {
                            // Guard chain overflow: Transition directly to wide execution
                            removeFastNode(currentFast, previousFast);

                            final WideDispatchDataNode<T> newWide = new WideDispatchDataNode<>(targetMethod, currentFast.dispatchDirectNode);
                            newWide.next = headWide;
                            headWide = insert(newWide);
                            return newWide.dispatchDirectNode;
                        }
                    }
                    previousFast = currentFast;
                    currentFast = currentFast.next;
                }

                // 2. Count Wide Chain
                totalMethodCount += countWideNodes();

                // 3. Global Budget Check
                if (totalMethodCount < CacheLimits.DISPATCH_CACHE_LIMIT) {
                    final FastDispatchDataNode<T> newNext = new FastDispatchDataNode<>(receiver, lookupResult, newDispatchNode);
                    if (previousFast == null) {
                        headFast = insert(newNext);
                    } else {
                        previousFast.next = previousFast.insert(newNext);
                    }
                    return newNext.dispatchDirectNode;
                }

                // Signals that the dispatch cache capacity is exhausted, requiring a transition to indirect execution.
                return convertToIndirect();
            } finally {
                // If pointers are null, it went indirect, which is handled in convertToIndirect()
                if (ENABLE_STATS && (this.headFast != null || this.headWide != null)) {
                    final int newFast = countFastNodes();
                    final int newWide = countWideNodes();

                    if (oldFast != newFast || oldWide != newWide) {
                        DISPATCH_HISTOGRAM[oldFast][oldWide].decrement();
                        DISPATCH_HISTOGRAM[newFast][newWide].increment();
                    }
                }
            }
        }

        @TruffleBoundary
        protected void removeFastNode(final FastDispatchDataNode<T> target, final FastDispatchDataNode<T> previous) {
            if (previous == null) {
                headFast = target.next;
            } else {
                previous.next = target.next;
            }
        }

        @TruffleBoundary
        protected int countFastNodes() {
            int count = 0;
            FastDispatchDataNode<T> current = headFast;
            while (current != null) {
                count++;
                current = current.next;
            }
            return count;
        }

        @TruffleBoundary
        protected int countWideNodes() {
            int count = 0;
            WideDispatchDataNode<T> current = headWide;
            while (current != null) {
                count++;
                current = current.next;
            }
            return count;
        }
    }

    public static final class FastDispatchDataNode<T extends AbstractDispatchDirectNode> extends Node {
        public final CompiledCodeObject standardMethodOrNull;
        @Child public GuardChainNode guardChainNode;
        @Child public T dispatchDirectNode;
        @Child public FastDispatchDataNode<T> next;

        public FastDispatchDataNode(final Object receiver, final Object lookupResult, final T dispatchNode) {
            this.guardChainNode = new GuardChainNode(receiver, dispatchNode.getAssumptions());
            this.standardMethodOrNull = lookupResult instanceof CompiledCodeObject m ? m : null;
            this.dispatchDirectNode = dispatchNode;
        }
    }

    public static final class WideDispatchDataNode<T extends AbstractDispatchDirectNode> extends Node {
        public final CompiledCodeObject standardMethod;
        @Child public T dispatchDirectNode;
        @Child public SqueakObjectClassNode classNode;
        @Child public WideDispatchDataNode<T> next;

        public WideDispatchDataNode(final CompiledCodeObject method, final T dispatchNode) {
            assert method != null : "Fallbacks must not enter the wide cache tier";
            this.standardMethod = method;
            this.dispatchDirectNode = dispatchNode;
            this.classNode = SqueakObjectClassNodeGen.create();
        }
    }

    public abstract static class AbstractGuardNode extends AbstractNode {
        public abstract boolean execute(Object receiver);

        public abstract boolean append(Object receiver, Assumption[] assumptions);
    }

    public static final class GuardChainNode extends AbstractGuardNode {
        @Child public GuardChainDataNode head;

        public GuardChainNode(final Object receiver, final Assumption[] assumptions) {
            this.head = new GuardChainDataNode(receiver, assumptions);
        }

        @Override
        @ExplodeLoop
        public boolean execute(final Object receiver) {
            GuardChainDataNode current = head;
            while (current != null) {
                // 1. Check Assumption Validity
                if (!Assumption.isValidAssumption(current.assumptions)) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    removeGuardNode(current);
                    // Note: If the head becomes null, the owning FastDispatchDataNode will be pruned during
                    // the next specialization pass.
                } else if (current.guard.check(receiver)) { // 2. Check Receiver Class
                    return true;
                }
                current = current.next;
            }
            return false;
        }

        @Override
        public boolean append(final Object receiver, final Assumption[] assumptions) {
            if (head == null) {
                head = insert(new GuardChainDataNode(receiver, assumptions));
                return true;
            }

            GuardChainDataNode current = head;
            int count = 1;
            while (current.next != null) {
                current = current.next;
                count++;
            }

            if (count < CacheLimits.LOOKUP_CACHE_LIMIT) {
                current.next = current.insert(new GuardChainDataNode(receiver, assumptions));
                return true;
            } else {
                return false;
            }
        }

        @TruffleBoundary
        protected void removeGuardNode(final GuardChainDataNode target) {
            GuardChainDataNode previous = null;
            GuardChainDataNode current = head;

            while (current != null) {
                if (current == target) {
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    return;
                }
                previous = current;
                current = current.next;
            }
        }
    }

    public static final class GuardChainDataNode extends Node {
        public final LookupClassGuard guard;
        @CompilationFinal(dimensions = 1) public final Assumption[] assumptions;

        @Child public GuardChainDataNode next;

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
