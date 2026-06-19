/*
 * Copyright (c) 2026 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2026 Oracle and/or its affiliates
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

import de.hpi.swa.trufflesqueak.image.SqueakImageContext;
import de.hpi.swa.trufflesqueak.model.ClassObject;
import de.hpi.swa.trufflesqueak.model.CompiledCodeObject;
import de.hpi.swa.trufflesqueak.model.NativeObject;
import de.hpi.swa.trufflesqueak.nodes.AbstractNode;
import de.hpi.swa.trufflesqueak.nodes.LookupMethodNode;
import de.hpi.swa.trufflesqueak.nodes.accessing.SqueakObjectClassNode;

public class AbstractDisNode {
    private static final int LOOKUP_CACHE_SIZE = 8;
    protected static final int DISPATCH_CACHE_SIZE = 4;

    public static final class FastDispatchDataNode<T extends Node> extends Node {
        public final CompiledCodeObject method;
        public final Assumption assumption;
        @Child public AbstractGuardNode guardChainNode;
        @Child public T dispatchDirectNode;
        @Child public FastDispatchDataNode<T> next;

        public FastDispatchDataNode(final Object receiver, final LookupResult result, final T dispatchNode) {
            this.guardChainNode = new GuardChainNode(receiver, result);
            this.method = result.method();
            this.assumption = method.getCallTargetStable();
            this.dispatchDirectNode = dispatchNode;
        }
    }

    public static final class MegaDispatchDataNode<T extends Node> extends Node {
        public final CompiledCodeObject method;
        @Child public T dispatchDirectNode;
        @Child public MegaDispatchDataNode<T> next; // <-- FIXED: @Child added

        public MegaDispatchDataNode(final CompiledCodeObject method, final T dispatchNode) {
            this.method = method;
            this.dispatchDirectNode = dispatchNode;
        }
    }

    public static final class DispatchCacheManager<T extends Node> extends Node {
        @Child public FastDispatchDataNode<T> headFast;
        @Child public MegaDispatchDataNode<T> headMegamorphic;

        // Returns the executor directly so the caller doesn't have to navigate nodes
        @TruffleBoundary
        protected T specialize(final Object receiver, final LookupResult result, final T newDispatchNode) {
            final CompiledCodeObject targetMethod = result.method();
            int totalMethodCount = 0;

            FastDispatchDataNode<T> previousFast = null;
            FastDispatchDataNode<T> currentFast = headFast;

            // 1. Scan Fast Chain
            while (currentFast != null) {
                totalMethodCount++;
                if (currentFast.method == targetMethod) {
                    if (currentFast.guardChainNode.append(receiver, result)) {
                        return currentFast.dispatchDirectNode;
                    } else {
                        if (previousFast == null) {
                            headFast = currentFast.next;
                        } else {
                            previousFast.next = currentFast.next;
                        }

                        final MegaDispatchDataNode<T> newMega = new MegaDispatchDataNode<>(targetMethod, currentFast.dispatchDirectNode);
                        newMega.next = headMegamorphic;
                        headMegamorphic = insert(newMega);

                        return newMega.dispatchDirectNode;
                    }
                }
                previousFast = currentFast;
                currentFast = currentFast.next;
            }

            // 2. Count Megamorphic Chain
            MegaDispatchDataNode<T> currentMega = headMegamorphic;
            while (currentMega != null) {
                totalMethodCount++;
                currentMega = currentMega.next;
            }

            // 3. Global Budget Check
            if (totalMethodCount < DISPATCH_CACHE_SIZE) {
                final FastDispatchDataNode<T> newNext = new FastDispatchDataNode<>(receiver, result, newDispatchNode);
                if (previousFast == null) {
                    headFast = insert(newNext);
                } else {
                    previousFast.next = previousFast.insert(newNext);
                }
                return newNext.dispatchDirectNode;
            }
            return null; // Signals absolute megamorphic cliff
        }

        protected void removeFastNode(final FastDispatchDataNode<T> target) {
            FastDispatchDataNode<T> previous = null;
            FastDispatchDataNode<T> current = headFast;
            while (current != null) {
                if (current == target) {
                    if (previous == null) {
                        headFast = current.next;
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

    public abstract static class AbstractGuardNode extends AbstractNode {
        abstract boolean execute(Object receiver);

        abstract boolean append(Object receiver, LookupResult result);
    }

    static class GuardChainNode extends AbstractGuardNode {
        @Child private GuardChainDataNode head;

        GuardChainNode(final Object receiver, final LookupResult result) {
            this.head = new GuardChainDataNode(receiver, result);
        }

        @Override
        @ExplodeLoop
        boolean execute(final Object receiver) {
            GuardChainDataNode current = head;
            while (current != null) {
                if (!Assumption.isValidAssumption(current.assumptions)) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    remove(current);
                } else if (current.guard.check(receiver)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }

        void remove(final GuardChainDataNode target) {
            assert head != null;
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

        @Override
        boolean append(final Object receiver, final LookupResult result) {
            if (head == null) {
                head = insert(new GuardChainDataNode(receiver, result));
                return true;
            }
            GuardChainDataNode current = head;
            int count = 1;
            while (current.next != null) {
                current = current.next;
                count++;
            }
            if (count < LOOKUP_CACHE_SIZE) {
                current.next = current.insert(new GuardChainDataNode(receiver, result));
                return true;
            } else {
                return false;
            }
        }
    }

    static class GuardChainDataNode extends AbstractNode {
        final LookupClassGuard guard;
        @CompilationFinal(dimensions = 1) final Assumption[] assumptions;

        @Child GuardChainDataNode next;

        GuardChainDataNode(final Object receiver, final LookupResult result) {
            guard = LookupClassGuard.create(receiver);
            assumptions = DispatchUtils.createAssumptions(result.receiverClass(), result.method());
        }
    }

    public static LookupResult resolveTargetMethod(final AbstractNode contextNode, final Object receiver, final NativeObject selector) {
        final ClassObject receiverClass = SqueakObjectClassNode.executeUncached(receiver);
        final SqueakImageContext image = contextNode.getContext();

        final Object lookupResult = image.lookup(receiverClass, selector);

        if (lookupResult instanceof final CompiledCodeObject lookupMethod) {
            return new LookupResult(selector, receiverClass, lookupResult, lookupMethod, LookupKind.STANDARD_METHOD, null);
        } else if (lookupResult == null) {
            return new LookupResult(selector, receiverClass, lookupResult, receiverClass.resolveDispatchFailure(selector), LookupKind.DOES_NOT_UNDERSTAND, null);
        } else {
            final ClassObject lookupResultClass = SqueakObjectClassNode.executeUncached(lookupResult);
            final Object runWithInLookupResult = LookupMethodNode.executeUncached(lookupResultClass, image.runWithInSelector);

            if (runWithInLookupResult instanceof final CompiledCodeObject runWithInMethod) {
                return new LookupResult(selector, receiverClass, lookupResult, runWithInMethod, LookupKind.OBJECT_AS_METHOD, lookupResult);
            } else {
                return new LookupResult(selector, receiverClass, lookupResult, lookupResultClass.resolveDispatchFailure(selector), LookupKind.DOES_NOT_UNDERSTAND, null);
            }
        }
    }

    protected record LookupResult(NativeObject selector, ClassObject receiverClass, Object lookupResult, CompiledCodeObject method, LookupKind kind, Object targetObject) {
    }

    protected enum LookupKind {
        STANDARD_METHOD,
        DOES_NOT_UNDERSTAND,
        OBJECT_AS_METHOD,
    }
}
