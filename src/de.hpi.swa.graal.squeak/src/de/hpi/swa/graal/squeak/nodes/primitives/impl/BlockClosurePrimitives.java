package de.hpi.swa.graal.squeak.nodes.primitives.impl;

import java.util.List;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.frame.VirtualFrame;

import de.hpi.swa.graal.squeak.exceptions.SqueakException;
import de.hpi.swa.graal.squeak.model.AbstractSqueakObject;
import de.hpi.swa.graal.squeak.model.BlockClosureObject;
import de.hpi.swa.graal.squeak.model.CompiledCodeObject;
import de.hpi.swa.graal.squeak.model.CompiledMethodObject;
import de.hpi.swa.graal.squeak.model.ContextObject;
import de.hpi.swa.graal.squeak.model.NilObject;
import de.hpi.swa.graal.squeak.model.ObjectLayouts.CONTEXT;
import de.hpi.swa.graal.squeak.model.PointersObject;
import de.hpi.swa.graal.squeak.nodes.BlockActivationNode;
import de.hpi.swa.graal.squeak.nodes.BlockActivationNodeGen;
import de.hpi.swa.graal.squeak.nodes.GetBlockFrameArgumentsNode;
import de.hpi.swa.graal.squeak.nodes.GetOrCreateContextNode;
import de.hpi.swa.graal.squeak.nodes.context.frame.FrameSlotReadNode;
import de.hpi.swa.graal.squeak.nodes.primitives.AbstractPrimitiveFactoryHolder;
import de.hpi.swa.graal.squeak.nodes.primitives.AbstractPrimitiveNode;
import de.hpi.swa.graal.squeak.nodes.primitives.SqueakPrimitive;
import de.hpi.swa.graal.squeak.util.FrameAccess;

public final class BlockClosurePrimitives extends AbstractPrimitiveFactoryHolder {

    @Override
    public List<NodeFactory<? extends AbstractPrimitiveNode>> getFactories() {
        return BlockClosurePrimitivesFactory.getFactories();
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 195)
    protected abstract static class PrimFindNextUnwindContextUpToNode extends AbstractPrimitiveNode {
        @Child private FrameSlotReadNode contextOrMarkerNode = FrameSlotReadNode.createForContextOrMarker();
        @Child private GetOrCreateContextNode contextNode = GetOrCreateContextNode.create();

        public PrimFindNextUnwindContextUpToNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization(guards = {"receiver.hasVirtualSender()"})
        protected final Object doFindNextVirtualized(final ContextObject receiver, final ContextObject previousContext) {
            final ContextObject handlerContext = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<ContextObject>() {
                boolean foundMyself = false;

                @Override
                public ContextObject visitFrame(final FrameInstance frameInstance) {
                    final Frame current = frameInstance.getFrame(FrameInstance.FrameAccess.MATERIALIZE);
                    if (current.getArguments().length < FrameAccess.RECEIVER) {
                        return null;
                    }
                    final Object contextOrMarker = contextOrMarkerNode.executeRead(current);
                    if (!foundMyself) {
                        if (receiver.equals(contextOrMarker)) {
                            foundMyself = true;
                        }
                    } else {
                        if (previousContext != null && previousContext.equals(contextOrMarker)) {
                            return null;
                        } else {
                            final CompiledCodeObject frameMethod = FrameAccess.getMethod(current);
                            if (frameMethod.isUnwindMarked()) {
                                return contextNode.executeGet(current, false);
                            }
                        }
                    }
                    return null;
                }
            });
            if (handlerContext == null) {
                return code.image.nil;
            } else {
                return handlerContext;
            }
        }

        @Specialization(guards = {"receiver.hasVirtualSender()"})
        protected final Object doFindNextVirtualizedNil(final ContextObject receiver, @SuppressWarnings("unused") final NilObject nil) {
            return doFindNextVirtualized(receiver, null);
        }

        @Specialization(guards = {"!receiver.hasVirtualSender()"})
        protected final Object doFindNext(final ContextObject receiver, final AbstractSqueakObject previousContextOrNil) {
            ContextObject current = receiver;
            while (current != previousContextOrNil) {
                final AbstractSqueakObject sender = current.getSender();
                if (sender.isNil() || sender == previousContextOrNil) {
                    break;
                } else {
                    current = (ContextObject) sender;
                    if (current.isUnwindContext()) {
                        return current;
                    }
                }
            }
            return code.image.nil;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 196)
    protected abstract static class PrimTerminateToNode extends AbstractPrimitiveNode {

        public PrimTerminateToNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization
        protected static final Object doTerminate(final ContextObject receiver, final ContextObject previousContext) {
            return terminateTo(receiver, previousContext);
        }

        @Specialization
        protected static final Object doTerminate(final ContextObject receiver, final NilObject nil) {
            return terminateTo(receiver, nil);
        }

        /*
         * Terminate all the Contexts between me and previousContext, if previousContext is on my
         * Context stack. Make previousContext my sender.
         */
        private static Object terminateTo(final ContextObject receiver, final AbstractSqueakObject previousContext) {
            if (hasSender(receiver, previousContext)) {
                ContextObject currentContext = receiver.getNotNilSender();
                while (currentContext != previousContext) {
                    final ContextObject sendingContext = currentContext.getNotNilSender();
                    currentContext.terminate();
                    currentContext = sendingContext;
                }
            }
            receiver.atput0(CONTEXT.SENDER_OR_NIL, previousContext); // flagging context as dirty
            return receiver;
        }

        /*
         * Answer whether the receiver is strictly above context on the stack (Context>>hasSender:).
         */
        private static boolean hasSender(final ContextObject context, final AbstractSqueakObject previousContext) {
            if (context == previousContext) {
                return false;
            }
            AbstractSqueakObject sender = context.getSender();
            while (!sender.isNil()) {
                if (sender == previousContext) {
                    return true;
                }
                sender = ((ContextObject) sender).getSender();
            }
            return false;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 197)
    protected abstract static class PrimNextHandlerContextNode extends AbstractPrimitiveNode {
        @Child private FrameSlotReadNode contextOrMarkerNode = FrameSlotReadNode.createForContextOrMarker();
        @Child private GetOrCreateContextNode contextNode = GetOrCreateContextNode.create();

        protected PrimNextHandlerContextNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization(guards = {"receiver.hasVirtualSender()"})
        protected final Object findNextVirtualized(final ContextObject receiver) {
            final ContextObject handlerContext = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<ContextObject>() {
                boolean foundMyself = false;

                @Override
                public ContextObject visitFrame(final FrameInstance frameInstance) {
                    final Frame current = frameInstance.getFrame(FrameInstance.FrameAccess.MATERIALIZE);
                    if (current.getArguments().length < FrameAccess.RECEIVER) {
                        return null;
                    }
                    if (!foundMyself) {
                        final Object contextOrMarker = contextOrMarkerNode.executeRead(current);
                        if (receiver.equals(contextOrMarker)) {
                            foundMyself = true;
                        }
                    } else {
                        final CompiledCodeObject frameMethod = FrameAccess.getMethod(current);
                        if (frameMethod.isExceptionHandlerMarked()) {
                            return contextNode.executeGet(current, false);
                        }
                    }
                    return null;
                }
            });
            if (handlerContext == null) {
                return code.image.nil;
            } else {
                return handlerContext;
            }
        }

        @Specialization(guards = {"!receiver.hasVirtualSender()"})
        protected final Object findNext(final ContextObject receiver) {
            ContextObject context = receiver;
            while (true) {
                if (context.getMethod().isExceptionHandlerMarked()) {
                    return context;
                }
                final AbstractSqueakObject sender = context.getSender();
                if (sender instanceof ContextObject) {
                    context = (ContextObject) sender;
                } else {
                    assert sender == code.image.nil;
                    return code.image.nil;
                }
            }
        }

    }

    private abstract static class AbstractClosureValuePrimitiveNode extends AbstractPrimitiveNode {
        @Child protected BlockActivationNode dispatch = BlockActivationNodeGen.create();

        protected AbstractClosureValuePrimitiveNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 200)
    public abstract static class PrimClosureCopyWithCopiedValuesNode extends AbstractPrimitiveNode {

        protected PrimClosureCopyWithCopiedValuesNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @SuppressWarnings("unused")
        @Specialization
        protected static final Object doCopy(final VirtualFrame frame, final ContextObject outerContext, final long numArgs, final PointersObject copiedValues) {
            throw new SqueakException("Not implemented and not used in Squeak anymore");
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 201)
    public abstract static class PrimClosureValue0Node extends AbstractClosureValuePrimitiveNode {

        protected PrimClosureValue0Node(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization
        protected final Object doClosure(final VirtualFrame frame, final BlockClosureObject block,
                        @Cached("create()") GetBlockFrameArgumentsNode getFrameArguments) {
            return dispatch.executeBlock(block, getFrameArguments.execute(block, getContextOrMarker(frame), new Object[0]));
        }

        // Additional specializations to speed up eager sends
        @Specialization
        protected static final Object doBoolean(final boolean receiver) {
            return receiver;
        }

        @Specialization
        protected static final Object doNilObject(final NilObject receiver) {
            return receiver;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 202)
    protected abstract static class PrimClosureValue1Node extends AbstractClosureValuePrimitiveNode {

        protected PrimClosureValue1Node(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization
        protected final Object doValue(final VirtualFrame frame, final BlockClosureObject block, final Object arg,
                        @Cached("create()") GetBlockFrameArgumentsNode getFrameArguments) {
            return dispatch.executeBlock(block, getFrameArguments.execute(block, getContextOrMarker(frame), new Object[]{arg}));
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 203)
    protected abstract static class PrimClosureValue2Node extends AbstractClosureValuePrimitiveNode {

        protected PrimClosureValue2Node(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization
        protected final Object doValue(final VirtualFrame frame, final BlockClosureObject block, final Object arg1, final Object arg2,
                        @Cached("create()") GetBlockFrameArgumentsNode getFrameArguments) {
            return dispatch.executeBlock(block, getFrameArguments.execute(block, getContextOrMarker(frame), new Object[]{arg1, arg2}));
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 204)
    protected abstract static class PrimClosureValue3Node extends AbstractClosureValuePrimitiveNode {

        protected PrimClosureValue3Node(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization
        protected final Object doValue(final VirtualFrame frame, final BlockClosureObject block, final Object arg1, final Object arg2, final Object arg3,
                        @Cached("create()") GetBlockFrameArgumentsNode getFrameArguments) {
            return dispatch.executeBlock(block, getFrameArguments.execute(block, getContextOrMarker(frame), new Object[]{arg1, arg2, arg3}));
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 205)
    protected abstract static class PrimClosureValue4Node extends AbstractClosureValuePrimitiveNode {

        protected PrimClosureValue4Node(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization
        protected final Object doValue(final VirtualFrame frame, final BlockClosureObject block, final Object arg1, final Object arg2, final Object arg3, final Object arg4,
                        @Cached("create()") GetBlockFrameArgumentsNode getFrameArguments) {
            return dispatch.executeBlock(block, getFrameArguments.execute(block, getContextOrMarker(frame), new Object[]{arg1, arg2, arg3, arg4}));
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 206)
    protected abstract static class PrimClosureValueAryNode extends AbstractClosureValuePrimitiveNode {

        protected PrimClosureValueAryNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization
        protected final Object value(final VirtualFrame frame, final BlockClosureObject block, final PointersObject argArray,
                        @Cached("create()") GetBlockFrameArgumentsNode getFrameArguments) {
            return dispatch.executeBlock(block, getFrameArguments.execute(block, getContextOrMarker(frame), argArray.getPointers()));
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 212)
    protected abstract static class PrimContextSizeNode extends AbstractPrimitiveNode {

        protected PrimContextSizeNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization
        protected static final long doSize(final ContextObject receiver) {
            return receiver.size() - receiver.instsize();
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 221)
    public abstract static class PrimClosureValueNoContextSwitchNode extends AbstractClosureValuePrimitiveNode {

        protected PrimClosureValueNoContextSwitchNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization
        protected final Object doClosure(final VirtualFrame frame, final BlockClosureObject block,
                        @Cached("create()") GetBlockFrameArgumentsNode getFrameArguments) {
            code.image.interrupt.disable();
            try {
                return dispatch.executeBlock(block, getFrameArguments.execute(block, getContextOrMarker(frame), new Object[0]));
            } finally {
                code.image.interrupt.enable();
            }
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 222)
    protected abstract static class PrimClosureValueAryNoContextSwitchNode extends AbstractClosureValuePrimitiveNode {

        protected PrimClosureValueAryNoContextSwitchNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization
        protected final Object doValue(final VirtualFrame frame, final BlockClosureObject block, final PointersObject argArray,
                        @Cached("create()") GetBlockFrameArgumentsNode getFrameArguments) {
            code.image.interrupt.disable();
            try {
                return dispatch.executeBlock(block, getFrameArguments.execute(block, getContextOrMarker(frame), argArray.getPointers()));
            } finally {
                code.image.interrupt.enable();
            }
        }
    }
}
