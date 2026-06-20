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
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;

import de.hpi.swa.trufflesqueak.exceptions.PrimitiveFailed;
import de.hpi.swa.trufflesqueak.image.SqueakImageContext;
import de.hpi.swa.trufflesqueak.model.ClassObject;
import de.hpi.swa.trufflesqueak.model.CompiledCodeObject;
import de.hpi.swa.trufflesqueak.model.NativeObject;
import de.hpi.swa.trufflesqueak.model.PointersObject;
import de.hpi.swa.trufflesqueak.nodes.AbstractNode;
import de.hpi.swa.trufflesqueak.nodes.accessing.SqueakObjectClassNode;
import de.hpi.swa.trufflesqueak.nodes.context.GetOrCreateContextWithoutFrameNode;
import de.hpi.swa.trufflesqueak.nodes.dispatch.AbstractDisNode.DispatchCacheManager;
import de.hpi.swa.trufflesqueak.nodes.dispatch.AbstractDisNode.FastDispatchDataNode;
import de.hpi.swa.trufflesqueak.nodes.dispatch.AbstractDisNode.LookupResult;
import de.hpi.swa.trufflesqueak.nodes.dispatch.AbstractDisNode.MegaDispatchDataNode;
import de.hpi.swa.trufflesqueak.nodes.dispatch.Dis1NodeFactory.IndirectDis1NodeGen;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector1Node.DispatchDirectPrimitiveFallback1Node;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector1Node.DispatchIndirect1Node.CreateFrameArgumentsForIndirectCall1Node;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector1Node.DispatchIndirect1Node.TryPrimitive1Node;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector1NodeFactory.DispatchDirectPrimitiveFallback1NodeGen;
import de.hpi.swa.trufflesqueak.nodes.primitives.AbstractPrimitiveNode;
import de.hpi.swa.trufflesqueak.nodes.primitives.Primitive.Primitive1;
import de.hpi.swa.trufflesqueak.nodes.primitives.PrimitiveNodeFactory;
import de.hpi.swa.trufflesqueak.util.FrameAccess;

import static de.hpi.swa.trufflesqueak.nodes.dispatch.AbstractDisNode.resolveTargetMethod;

public final class Dis1Node extends AbstractDispatchNode {
    @Child private AbstractDis1Node dispatchNode = new DirectDis1Node();

    Dis1Node(final NativeObject selector) {
        super(selector);
    }

    @NeverDefault
    public static Dis1Node create(final NativeObject selector) {
        return new Dis1Node(selector);
    }

    public Object execute(final VirtualFrame frame, final Object receiver, final Object arg1) {
        if (dispatchNode instanceof final DirectDis1Node directNode) {
            return directNode.execute(frame, receiver, arg1);
        } else {
            return ((IndirectDis1Node) dispatchNode).execute(frame, selector, receiver, arg1);
        }
    }

    abstract static class AbstractDis1Node extends AbstractNode {
    }

    public abstract static class Dispatch1Node extends AbstractNode {
        static Dispatch1Node create(final LookupResult result) {
            return switch (result.kind()) {
                case STANDARD_METHOD -> {
                    final CompiledCodeObject method = result.method();
                    if (method.hasPrimitive()) {
                        final AbstractPrimitiveNode primitiveNode = PrimitiveNodeFactory.getOrCreateIndexedOrNamed(method);
                        if (primitiveNode instanceof final Primitive1 primitive1) {
                            yield new DispatchDirectPrimitive1Node(method, primitive1);
                        }
                        DispatchUtils.logMissingPrimitive(primitiveNode, method);
                    }
                    yield new DispatchDirectMethod1Node(method);
                }
                case DOES_NOT_UNDERSTAND -> new DispatchDirectMessageFallback1Node(result);
                case OBJECT_AS_METHOD -> new DispatchDirectObjectAsMethod1Node(result);
            };
        }

        public abstract Object execute(VirtualFrame frame, Object receiver, Object arg1);

        static final class DispatchDirectPrimitive1Node extends Dispatch1Node {
            @Child private Primitive1 primitiveNode;
            @Child private DispatchDirectPrimitiveFallback1Node dispatchFallbackNode;

            DispatchDirectPrimitive1Node(final CompiledCodeObject method, final Primitive1 primitiveNode) {
                this.primitiveNode = primitiveNode;
                dispatchFallbackNode = DispatchDirectPrimitiveFallback1NodeGen.create(method);
            }

            @Override
            public Object execute(final VirtualFrame frame, final Object receiver, final Object arg1) {
                try {
                    return primitiveNode.execute(frame, receiver, arg1);
                } catch (final PrimitiveFailed pf) {
                    DispatchUtils.logPrimitiveFailed(primitiveNode);
                    return dispatchFallbackNode.execute(frame, receiver, arg1, pf);
                }
            }
        }

        abstract static class DispatchWithSender1Node extends Dispatch1Node {
            @Child protected GetOrCreateContextWithoutFrameNode senderNode = GetOrCreateContextWithoutFrameNode.create();
        }

        static final class DispatchDirectMethod1Node extends DispatchWithSender1Node {
            @Child private DirectCallNode callNode;

            DispatchDirectMethod1Node(final CompiledCodeObject method) {
                callNode = DirectCallNode.create(method.getCallTarget());
            }

            @Override
            public Object execute(final VirtualFrame frame, final Object receiver, final Object arg1) {
                return callNode.call(FrameAccess.newWith(senderNode.execute(frame), null, receiver, arg1));
            }
        }

        static final class DispatchDirectMessageFallback1Node extends DispatchWithSender1Node {
            private final NativeObject selector;
            @Child private DirectCallNode callNode;
            @Child private CreateMessageNode createMessageNode = CreateMessageNodeGen.create();

            DispatchDirectMessageFallback1Node(final LookupResult result) {
                this.selector = result.selector();
                callNode = DirectCallNode.create(result.method().getCallTarget());
            }

            @Override
            public Object execute(final VirtualFrame frame, final Object receiver, final Object arg1) {
                final PointersObject message = createMessageNode.execute(selector, receiver, new Object[]{arg1});
                return callNode.call(FrameAccess.newMessageFallbackWith(senderNode.execute(frame), receiver, message));
            }
        }

        static final class DispatchDirectObjectAsMethod1Node extends DispatchWithSender1Node {
            private final NativeObject selector;
            private final Object targetObject;
            @Child private DirectCallNode callNode;

            DispatchDirectObjectAsMethod1Node(final LookupResult result) {
                this.selector = result.selector();
                callNode = DirectCallNode.create(result.method().getCallTarget());
                this.targetObject = result.targetObject();
            }

            @Override
            public Object execute(final VirtualFrame frame, final Object receiver, final Object arg1) {
                return callNode.call(FrameAccess.newOAMWith(senderNode.execute(frame), targetObject, selector, getContext().asArrayOfObjects(arg1), receiver));
            }
        }
    }

    static class DirectDis1Node extends AbstractDis1Node {
        @Child private DispatchCacheManager<Dispatch1Node> cache;
        @CompilationFinal private SelectorMegamorphicCache selectorCache;

        DirectDis1Node() {
            // Must be instantiated in constructor since selector is from parent
        }

        @ExplodeLoop
        Object execute(final VirtualFrame frame, final Object receiver, final Object arg1) {
            // Lazy initialization of the cache manager and the selector cache
            if (cache == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                cache = insert(new DispatchCacheManager<>());
                final NativeObject selector = ((Dis1Node) getParent()).selector;
                selectorCache = getContext().getSelectorCache(selector);
            }

            // TIER 1: Lean Fast Path
            FastDispatchDataNode<Dispatch1Node> currentFast = cache.headFast;
            while (currentFast != null) {
                if (!Assumption.isValidAssumption(currentFast.assumption)) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    cache.removeFastNode(currentFast);
                    return executeAndSpecialize(frame, receiver, arg1);
                }
                if (currentFast.guardChainNode.execute(receiver)) {
                    return currentFast.dispatchDirectNode.execute(frame, receiver, arg1);
                }
                currentFast = currentFast.next;
            }

            // TIER 2 & 3: Megamorphic Execution (PROTECTED BY NULL CHECK!)
            if (cache.headMegamorphic != null) {
                // We only do the heavy lookup if we ACTUALLY have megamorphic methods to check!
                final ClassObject receiverClass = SqueakObjectClassNode.executeUncached(receiver);
                final LookupResult result = selectorCache.lookupCached(this, receiverClass);

                MegaDispatchDataNode<Dispatch1Node> currentMega = cache.headMegamorphic;
                while (currentMega != null) {
                    if (currentMega.method == result.method()) {
                        return currentMega.dispatchDirectNode.execute(frame, receiver, arg1);
                    }
                    currentMega = currentMega.next;
                }
            }

            // TIER 4: Delegate Cache Miss to Centralized Manager
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return executeAndSpecialize(frame, receiver, arg1);
        }

        Object executeAndSpecialize(final VirtualFrame frame, final Object receiver, final Object arg1) {
            final ClassObject receiverClass = SqueakObjectClassNode.executeUncached(receiver);
            final LookupResult result = selectorCache.lookupCached(this, receiverClass);

            final Dispatch1Node aritySpecificNode = Dispatch1Node.create(result);
            final Dispatch1Node executor = cache.specialize(receiver, result, aritySpecificNode);
            if (executor != null) {
                return executor.execute(frame, receiver, arg1);
            } else {
                this.reportPolymorphicSpecialize();
                return replace(IndirectDis1NodeGen.create()).execute(frame, selectorCache.getSelector(), receiver, arg1);
            }
        }
    }

    @GenerateInline(false)
    abstract static class IndirectDis1Node extends AbstractDis1Node {
        public abstract Object execute(VirtualFrame frame, NativeObject selector, Object receiver, Object arg1);

        @Specialization
        protected static final Object doIndirect(final VirtualFrame frame, final NativeObject selector, final Object receiver, final Object arg1,
                        @Bind final Node node,
                        @SuppressWarnings("unused") @Bind final SqueakImageContext image,
                        @Cached(inline = true) final SqueakObjectClassNode classNode,
                        @Cached(value = "image.getSelectorCache(selector)", neverDefault = true) final SelectorMegamorphicCache selectorCache,
                        @Cached final TryPrimitive1Node tryPrimitiveNode,
                        @Cached(inline = true) final GetOrCreateContextWithoutFrameNode senderNode,
                        @Cached final CreateFrameArgumentsForIndirectCall1Node argumentsNode,
                        @Cached final IndirectCallNode callNode) {
            final ClassObject receiverClass = classNode.executeLookup(node, receiver);
            final LookupResult lookupResult = selectorCache.lookupCached((AbstractNode) node, receiverClass);
            final CompiledCodeObject method = lookupResult.method();

            final Object result = tryPrimitiveNode.execute(frame, method, receiver, arg1);
            if (result != null) {
                return result;
            } else {
                return callNode.call(method.getCallTarget(), argumentsNode.execute(node, senderNode.execute(frame, node), receiver, arg1, receiverClass, lookupResult.lookupResult(), selector));
            }
        }
    }
}
