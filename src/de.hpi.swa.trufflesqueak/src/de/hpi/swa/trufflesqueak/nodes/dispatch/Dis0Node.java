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
import de.hpi.swa.trufflesqueak.nodes.dispatch.Dis0NodeFactory.IndirectDis0NodeGen;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector0Node.DispatchDirectPrimitiveFallback0Node;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector0Node.DispatchIndirect0Node.CreateFrameArgumentsForIndirectCall0Node;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector0Node.DispatchIndirect0Node.TryPrimitive0Node;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector0NodeFactory.DispatchDirectPrimitiveFallback0NodeGen;
import de.hpi.swa.trufflesqueak.nodes.primitives.AbstractPrimitiveNode;
import de.hpi.swa.trufflesqueak.nodes.primitives.Primitive.Primitive0;
import de.hpi.swa.trufflesqueak.nodes.primitives.PrimitiveNodeFactory;
import de.hpi.swa.trufflesqueak.util.ArrayUtils;
import de.hpi.swa.trufflesqueak.util.FrameAccess;

import static de.hpi.swa.trufflesqueak.nodes.dispatch.AbstractDisNode.resolveTargetMethod;

public final class Dis0Node extends AbstractDispatchNode {
    @Child private AbstractDis0Node dispatchNode = new DirectDis0Node();

    Dis0Node(final NativeObject selector) {
        super(selector);
    }

    @NeverDefault
    public static Dis0Node create(final NativeObject selector) {
        return new Dis0Node(selector);
    }

    public Object execute(final VirtualFrame frame, final Object receiver) {
        if (dispatchNode instanceof final DirectDis0Node directNode) {
            return directNode.execute(frame, receiver);
        } else {
            return ((IndirectDis0Node) dispatchNode).execute(frame, selector, receiver);
        }
    }

    abstract static class AbstractDis0Node extends AbstractNode {
    }

    public abstract static class Dispatch0Node extends AbstractNode {
        static Dispatch0Node create(final LookupResult result) {
            return switch (result.kind()) {
                case STANDARD_METHOD -> {
                    final CompiledCodeObject method = result.method();
                    if (method.hasPrimitive()) {
                        final AbstractPrimitiveNode primitiveNode = PrimitiveNodeFactory.getOrCreateIndexedOrNamed(method);
                        if (primitiveNode instanceof final Primitive0 primitive0) {
                            yield new DispatchDirectPrimitive0Node(method, primitive0);
                        }
                        DispatchUtils.logMissingPrimitive(primitiveNode, method);
                    }
                    yield new DispatchDirectMethod0Node(method);
                }
                case DOES_NOT_UNDERSTAND -> new DispatchDirectMessageFallback0Node(result);
                case OBJECT_AS_METHOD -> new DispatchDirectObjectAsMethod0Node(result);
            };
        }

        public abstract Object execute(VirtualFrame frame, Object receiver);

        static final class DispatchDirectPrimitive0Node extends Dispatch0Node {
            @Child private Primitive0 primitiveNode;
            @Child private DispatchDirectPrimitiveFallback0Node dispatchFallbackNode;

            DispatchDirectPrimitive0Node(final CompiledCodeObject method, final Primitive0 primitiveNode) {
                this.primitiveNode = primitiveNode;
                dispatchFallbackNode = DispatchDirectPrimitiveFallback0NodeGen.create(method);
            }

            @Override
            public Object execute(final VirtualFrame frame, final Object receiver) {
                try {
                    return primitiveNode.execute(frame, receiver);
                } catch (final PrimitiveFailed pf) {
                    DispatchUtils.logPrimitiveFailed(primitiveNode);
                    return dispatchFallbackNode.execute(frame, receiver, pf);
                }
            }
        }

        abstract static class DispatchWithSender0Node extends Dispatch0Node {
            @Child protected GetOrCreateContextWithoutFrameNode senderNode = GetOrCreateContextWithoutFrameNode.create();
        }

        static final class DispatchDirectMethod0Node extends DispatchWithSender0Node {
            @Child private DirectCallNode callNode;

            DispatchDirectMethod0Node(final CompiledCodeObject method) {
                callNode = DirectCallNode.create(method.getCallTarget());
            }

            @Override
            public Object execute(final VirtualFrame frame, final Object receiver) {
                return callNode.call(FrameAccess.newWith(senderNode.execute(frame), null, receiver));
            }
        }

        static final class DispatchDirectMessageFallback0Node extends DispatchWithSender0Node {
            private final NativeObject selector;
            @Child private DirectCallNode callNode;
            @Child private CreateMessageNode createMessageNode = CreateMessageNodeGen.create();

            DispatchDirectMessageFallback0Node(final LookupResult result) {
                this.selector = result.selector();
                callNode = DirectCallNode.create(result.method().getCallTarget());
            }

            @Override
            public Object execute(final VirtualFrame frame, final Object receiver) {
                final PointersObject message = createMessageNode.execute(selector, receiver, ArrayUtils.EMPTY_ARRAY);
                return callNode.call(FrameAccess.newMessageFallbackWith(senderNode.execute(frame), receiver, message));
            }
        }

        static final class DispatchDirectObjectAsMethod0Node extends DispatchWithSender0Node {
            private final NativeObject selector;
            private final Object targetObject;
            @Child private DirectCallNode callNode;

            DispatchDirectObjectAsMethod0Node(final LookupResult result) {
                this.selector = result.selector();
                callNode = DirectCallNode.create(result.method().getCallTarget());
                this.targetObject = result.targetObject();
            }

            @Override
            public Object execute(final VirtualFrame frame, final Object receiver) {
                return callNode.call(FrameAccess.newOAMWith(senderNode.execute(frame), targetObject, selector, getContext().asArrayOfObjects(ArrayUtils.EMPTY_ARRAY), receiver));
            }
        }
    }

    static class DirectDis0Node extends AbstractDis0Node {
        @Child private DispatchCacheManager<Dispatch0Node> cache;
        @CompilationFinal private SelectorMegamorphicCache selectorCache;

        DirectDis0Node() {
            // Must be instantiated in constructor since selector is from parent
        }

        @ExplodeLoop
        Object execute(final VirtualFrame frame, final Object receiver) {
            // Lazy initialization of the cache manager and the selector cache
            if (cache == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                cache = insert(new DispatchCacheManager<>());
                final NativeObject selector = ((Dis0Node) getParent()).selector;
                selectorCache = getContext().getSelectorCache(selector);
            }

            // TIER 1: Lean Fast Path
            FastDispatchDataNode<Dispatch0Node> currentFast = cache.headFast;
            while (currentFast != null) {
                if (!Assumption.isValidAssumption(currentFast.assumption)) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    cache.removeFastNode(currentFast);
                    return executeAndSpecialize(frame, receiver);
                }
                if (currentFast.guardChainNode.execute(receiver)) {
                    return currentFast.dispatchDirectNode.execute(frame, receiver);
                }
                currentFast = currentFast.next;
            }

            // TIER 2 & 3: Megamorphic Execution (PROTECTED BY NULL CHECK!)
            if (cache.headMegamorphic != null) {
                // We only do the heavy lookup if we ACTUALLY have megamorphic methods to check!
                final ClassObject receiverClass = SqueakObjectClassNode.executeUncached(receiver);
                final LookupResult result = selectorCache.lookupCached(this, receiverClass);

                MegaDispatchDataNode<Dispatch0Node> currentMega = cache.headMegamorphic;
                while (currentMega != null) {
                    if (currentMega.method == result.method()) {
                        return currentMega.dispatchDirectNode.execute(frame, receiver);
                    }
                    currentMega = currentMega.next;
                }
            }

            // TIER 4: Delegate Cache Miss to Centralized Manager
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return executeAndSpecialize(frame, receiver);
        }

        Object executeAndSpecialize(final VirtualFrame frame, final Object receiver) {
            final ClassObject receiverClass = SqueakObjectClassNode.executeUncached(receiver);
            final LookupResult result = selectorCache.lookupCached(this, receiverClass);

            final Dispatch0Node aritySpecificNode = Dispatch0Node.create(result);
            final Dispatch0Node executor = cache.specialize(receiver, result, aritySpecificNode);
            if (executor != null) {
                return executor.execute(frame, receiver);
            } else {
                this.reportPolymorphicSpecialize();
                return replace(IndirectDis0NodeGen.create()).execute(frame, selectorCache.getSelector(), receiver);
            }
        }
    }

    @GenerateInline(false)
    abstract static class IndirectDis0Node extends AbstractDis0Node {
        public abstract Object execute(VirtualFrame frame, NativeObject selector, Object receiver);

        @Specialization
        protected static final Object doIndirect(final VirtualFrame frame, final NativeObject selector, final Object receiver,
                        @Bind final Node node,
                        @SuppressWarnings("unused") @Bind final SqueakImageContext image,
                        @Cached(inline = true) final SqueakObjectClassNode classNode,
                        @Cached(value = "image.getSelectorCache(selector)", neverDefault = true) final SelectorMegamorphicCache selectorCache,
                        @Cached final TryPrimitive0Node tryPrimitiveNode,
                        @Cached(inline = true) final GetOrCreateContextWithoutFrameNode senderNode,
                        @Cached final CreateFrameArgumentsForIndirectCall0Node argumentsNode,
                        @Cached final IndirectCallNode callNode) {
            final ClassObject receiverClass = classNode.executeLookup(node, receiver);
            final LookupResult lookupResult = selectorCache.lookupCached((AbstractNode) node, receiverClass);
            final CompiledCodeObject method = lookupResult.method();

            final Object result = tryPrimitiveNode.execute(frame, method, receiver);
            if (result != null) {
                return result;
            } else {
                return callNode.call(method.getCallTarget(), argumentsNode.execute(node, senderNode.execute(frame, node), receiver, receiverClass, lookupResult.lookupResult(), selector));
            }
        }
    }
}
