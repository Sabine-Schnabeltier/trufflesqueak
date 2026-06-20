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
import de.hpi.swa.trufflesqueak.nodes.dispatch.Dis2NodeFactory.IndirectDis2NodeGen;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector2Node.DispatchDirectPrimitiveFallback2Node;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector2Node.DispatchIndirect2Node.CreateFrameArgumentsForIndirectCall2Node;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector2Node.DispatchIndirect2Node.TryPrimitive2Node;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector2NodeFactory.DispatchDirectPrimitiveFallback2NodeGen;
import de.hpi.swa.trufflesqueak.nodes.primitives.AbstractPrimitiveNode;
import de.hpi.swa.trufflesqueak.nodes.primitives.Primitive.Primitive2;
import de.hpi.swa.trufflesqueak.nodes.primitives.PrimitiveNodeFactory;
import de.hpi.swa.trufflesqueak.util.FrameAccess;

import static de.hpi.swa.trufflesqueak.nodes.dispatch.AbstractDisNode.resolveTargetMethod;

public final class Dis2Node extends AbstractDispatchNode {

    @Child private AbstractDis2Node dispatchNode = new DirectDis2Node();

    Dis2Node(final NativeObject selector) {
        super(selector);
    }

    @NeverDefault
    public static Dis2Node create(final NativeObject selector) {
        return new Dis2Node(selector);
    }

    public Object execute(final VirtualFrame frame, final Object receiver, final Object arg1, final Object arg2) {
        if (dispatchNode instanceof final DirectDis2Node directNode) {
            return directNode.execute(frame, receiver, arg1, arg2);
        } else {
            return ((IndirectDis2Node) dispatchNode).execute(frame, selector, receiver, arg1, arg2);
        }
    }

    abstract static class AbstractDis2Node extends AbstractNode {
    }

    public abstract static class Dispatch2Node extends AbstractNode {
        static Dispatch2Node create(final LookupResult result) {
            return switch (result.kind()) {
                case STANDARD_METHOD -> {
                    final CompiledCodeObject method = result.method();
                    if (method.hasPrimitive()) {
                        final AbstractPrimitiveNode primitiveNode = PrimitiveNodeFactory.getOrCreateIndexedOrNamed(method);
                        if (primitiveNode instanceof final Primitive2 primitive2) {
                            yield new DispatchDirectPrimitive2Node(method, primitive2);
                        }
                        DispatchUtils.logMissingPrimitive(primitiveNode, method);
                    }
                    yield new DispatchDirectMethod2Node(method);
                }
                case DOES_NOT_UNDERSTAND -> new DispatchDirectMessageFallback2Node(result);
                case OBJECT_AS_METHOD -> new DispatchDirectObjectAsMethod2Node(result);
            };
        }

        public abstract Object execute(VirtualFrame frame, Object receiver, Object arg1, Object arg2);

        static final class DispatchDirectPrimitive2Node extends Dispatch2Node {
            @Child private Primitive2 primitiveNode;
            @Child private DispatchDirectPrimitiveFallback2Node dispatchFallbackNode;

            DispatchDirectPrimitive2Node(final CompiledCodeObject method, final Primitive2 primitiveNode) {
                this.primitiveNode = primitiveNode;
                dispatchFallbackNode = DispatchDirectPrimitiveFallback2NodeGen.create(method);
            }

            @Override
            public Object execute(final VirtualFrame frame, final Object receiver, final Object arg1, final Object arg2) {
                try {
                    return primitiveNode.execute(frame, receiver, arg1, arg2);
                } catch (final PrimitiveFailed pf) {
                    DispatchUtils.logPrimitiveFailed(primitiveNode);
                    return dispatchFallbackNode.execute(frame, receiver, arg1, arg2, pf);
                }
            }
        }

        abstract static class DispatchWithSender2Node extends Dispatch2Node {
            @Child protected GetOrCreateContextWithoutFrameNode senderNode = GetOrCreateContextWithoutFrameNode.create();
        }

        static final class DispatchDirectMethod2Node extends DispatchWithSender2Node {
            @Child private DirectCallNode callNode;

            DispatchDirectMethod2Node(final CompiledCodeObject method) {
                callNode = DirectCallNode.create(method.getCallTarget());
            }

            @Override
            public Object execute(final VirtualFrame frame, final Object receiver, final Object arg1, final Object arg2) {
                return callNode.call(FrameAccess.newWith(senderNode.execute(frame), null, receiver, arg1, arg2));
            }
        }

        static final class DispatchDirectMessageFallback2Node extends DispatchWithSender2Node {
            private final NativeObject selector;
            @Child private DirectCallNode callNode;
            @Child private CreateMessageNode createMessageNode = CreateMessageNodeGen.create();

            DispatchDirectMessageFallback2Node(final LookupResult result) {
                this.selector = result.selector();
                callNode = DirectCallNode.create(result.method().getCallTarget());
            }

            @Override
            public Object execute(final VirtualFrame frame, final Object receiver, final Object arg1, final Object arg2) {
                final PointersObject message = createMessageNode.execute(selector, receiver, new Object[]{arg1, arg2});
                return callNode.call(FrameAccess.newMessageFallbackWith(senderNode.execute(frame), receiver, message));
            }
        }

        static final class DispatchDirectObjectAsMethod2Node extends DispatchWithSender2Node {
            private final NativeObject selector;
            private final Object targetObject;
            @Child private DirectCallNode callNode;

            DispatchDirectObjectAsMethod2Node(final LookupResult result) {
                this.selector = result.selector();
                callNode = DirectCallNode.create(result.method().getCallTarget());
                this.targetObject = result.targetObject();
            }

            @Override
            public Object execute(final VirtualFrame frame, final Object receiver, final Object arg1, final Object arg2) {
                return callNode.call(FrameAccess.newOAMWith(senderNode.execute(frame), targetObject, selector, getContext().asArrayOfObjects(arg1, arg2), receiver));
            }
        }
    }

    static class DirectDis2Node extends AbstractDis2Node {
        @Child private DispatchCacheManager<Dispatch2Node> cache;
        @CompilationFinal private SelectorMegamorphicCache selectorCache;

        DirectDis2Node() {
            // Must be instantiated in constructor since selector is from parent
        }

        @ExplodeLoop
        Object execute(final VirtualFrame frame, final Object receiver, final Object arg1, final Object arg2) {
            // Lazy initialization of the cache manager and the selector cache
            if (cache == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                cache = insert(new DispatchCacheManager<>());
                final NativeObject selector = ((Dis2Node) getParent()).selector;
                selectorCache = getContext().getSelectorCache(selector);
            }

            // TIER 1: Lean Fast Path
            FastDispatchDataNode<Dispatch2Node> currentFast = cache.headFast;
            while (currentFast != null) {
                if (!Assumption.isValidAssumption(currentFast.assumption)) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    cache.removeFastNode(currentFast);
                    return executeAndSpecialize(frame, receiver, arg1, arg2);
                }
                if (currentFast.guardChainNode.execute(receiver)) {
                    return currentFast.dispatchDirectNode.execute(frame, receiver, arg1, arg2);
                }
                currentFast = currentFast.next;
            }

            // TIER 2 & 3: Megamorphic Execution (PROTECTED BY NULL CHECK!)
            if (cache.headMegamorphic != null) {
                // We only do the heavy lookup if we ACTUALLY have megamorphic methods to check!
                final ClassObject receiverClass = SqueakObjectClassNode.executeUncached(receiver);
                final LookupResult result = selectorCache.lookupCached(this, receiverClass);

                MegaDispatchDataNode<Dispatch2Node> currentMega = cache.headMegamorphic;
                while (currentMega != null) {
                    if (currentMega.method == result.method()) {
                        return currentMega.dispatchDirectNode.execute(frame, receiver, arg1, arg2);
                    }
                    currentMega = currentMega.next;
                }
            }

            // TIER 4: Delegate Cache Miss to Centralized Manager
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return executeAndSpecialize(frame, receiver, arg1, arg2);
        }

        Object executeAndSpecialize(final VirtualFrame frame, final Object receiver, final Object arg1, final Object arg2) {
            final ClassObject receiverClass = SqueakObjectClassNode.executeUncached(receiver);
            final LookupResult result = selectorCache.lookupCached(this, receiverClass);

            final Dispatch2Node aritySpecificNode = Dispatch2Node.create(result);
            final Dispatch2Node executor = cache.specialize(receiver, result, aritySpecificNode);
            if (executor != null) {
                return executor.execute(frame, receiver, arg1, arg2);
            } else {
                this.reportPolymorphicSpecialize();
                return replace(IndirectDis2NodeGen.create()).execute(frame, selectorCache.getSelector(), receiver, arg1, arg2);
            }
        }
    }

    @GenerateInline(false)
    abstract static class IndirectDis2Node extends AbstractDis2Node {
        public abstract Object execute(VirtualFrame frame, NativeObject selector, Object receiver, Object arg1, Object arg2);

        @Specialization
        protected static final Object doIndirect(final VirtualFrame frame, final NativeObject selector, final Object receiver, final Object arg1, final Object arg2,
                        @Bind final Node node,
                        @SuppressWarnings("unused") @Bind final SqueakImageContext image,
                        @Cached(inline = true) final SqueakObjectClassNode classNode,
                        @Cached(value = "image.getSelectorCache(selector)", neverDefault = true) final SelectorMegamorphicCache selectorCache,
                        @Cached final TryPrimitive2Node tryPrimitiveNode,
                        @Cached(inline = true) final GetOrCreateContextWithoutFrameNode senderNode,
                        @Cached final CreateFrameArgumentsForIndirectCall2Node argumentsNode,
                        @Cached final IndirectCallNode callNode) {
            final ClassObject receiverClass = classNode.executeLookup(node, receiver);
            final LookupResult lookupResult = selectorCache.lookupCached((AbstractNode) node, receiverClass);
            final CompiledCodeObject method = lookupResult.method();

            final Object result = tryPrimitiveNode.execute(frame, method, receiver, arg1, arg2);
            if (result != null) {
                return result;
            } else {
                return callNode.call(method.getCallTarget(), argumentsNode.execute(node, senderNode.execute(frame, node), receiver, arg1, arg2, receiverClass, lookupResult.lookupResult(), selector));
            }
        }
    }
}
