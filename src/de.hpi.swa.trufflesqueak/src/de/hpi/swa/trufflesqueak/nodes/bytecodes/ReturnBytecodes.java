/*
 * Copyright (c) 2017-2025 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021-2025 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.bytecodes;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.ConditionProfile;

import de.hpi.swa.trufflesqueak.exceptions.Returns.CannotReturnToTarget;
import de.hpi.swa.trufflesqueak.exceptions.Returns.NonLocalReturn;
import de.hpi.swa.trufflesqueak.exceptions.Returns.NonVirtualReturn;
import de.hpi.swa.trufflesqueak.exceptions.SqueakExceptions.SqueakException;
import de.hpi.swa.trufflesqueak.model.AbstractSqueakObject;
import de.hpi.swa.trufflesqueak.model.BooleanObject;
import de.hpi.swa.trufflesqueak.model.CompiledCodeObject;
import de.hpi.swa.trufflesqueak.model.ContextObject;
import de.hpi.swa.trufflesqueak.model.NilObject;
import de.hpi.swa.trufflesqueak.nodes.AbstractNode;
import de.hpi.swa.trufflesqueak.nodes.context.frame.FrameStackPopNode;
import de.hpi.swa.trufflesqueak.nodes.context.frame.GetOrCreateContextNode;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector2Node.Dispatch2Node;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector2NodeFactory.Dispatch2NodeGen;
import de.hpi.swa.trufflesqueak.util.FrameAccess;
import de.hpi.swa.trufflesqueak.util.LogUtils;

public final class ReturnBytecodes {

    public abstract static class AbstractReturnNode extends AbstractBytecodeNode {
        protected AbstractReturnNode(final CompiledCodeObject code, final int index) {
            super(code, index);
        }

        @Override
        public final void executeVoid(final VirtualFrame frame) {
            throw SqueakException.create("executeReturn() should be called instead");
        }

        public final Object executeReturn(final VirtualFrame frame) {
            return executeReturnSpecialized(frame);
        }

        protected abstract Object executeReturnSpecialized(VirtualFrame frame);

        protected abstract Object getReturnValue(VirtualFrame frame);
    }

    public abstract static class AbstractNormalReturnNode extends AbstractReturnNode {
        @Child private AbstractReturnKindNode returnNode;

        protected AbstractNormalReturnNode(final VirtualFrame frame, final CompiledCodeObject code, final int index) {
            super(code, index);
            returnNode = FrameAccess.hasClosure(frame) ? new ReturnFromClosureNode() : new ReturnFromMethodNode();
        }

        @Override
        public final Object executeReturnSpecialized(final VirtualFrame frame) {
            return returnNode.execute(frame, getReturnValue(frame));
        }
    }

    private abstract static class AbstractReturnKindNode extends AbstractNode {
        protected abstract Object execute(VirtualFrame frame, Object returnValue);
    }

    private static final class ReturnFromMethodNode extends AbstractReturnKindNode {

        /* Return to sender (never needs to unwind) */

        private final ConditionProfile hasModifiedSenderProfile = ConditionProfile.create();

        @Override
        protected Object execute(final VirtualFrame frame, final Object returnValue) {
            assert !FrameAccess.hasClosure(frame);
            if (hasModifiedSenderProfile.profile(FrameAccess.hasModifiedSender(frame))) {
                // ToDo: It may be that the sender is always materialized, but it is not a
                // requirement at this time.
                assert FrameAccess.getSender(frame) instanceof ContextObject : "Sender must be a materialized ContextObject";
                throw new NonVirtualReturn(returnValue, FrameAccess.getSender(frame), null);
            } else {
                return returnValue;
            }
        }
    }

    private static final class ReturnFromClosureNode extends AbstractReturnKindNode {
        @Child private GetOrCreateContextNode getOrCreateContextNode;
        @Child private Dispatch2Node sendAboutToReturnNode;

        /* Return to closure's home context's sender, executing unwind blocks */

        @Override
        protected Object execute(final VirtualFrame frame, final Object returnValue) {
            assert FrameAccess.hasClosure(frame);
            // Target is sender of closure's home context.
            final ContextObject homeContext = FrameAccess.getClosure(frame).getHomeContext();
            if (homeContext.canBeReturnedTo()) {
                final ContextObject firstMarkedContext = firstUnwindMarkedOrThrowNLR(homeContext, returnValue);
                if (firstMarkedContext != null) {
                    getSendAboutToReturnNode().execute(frame, getGetOrCreateContextNode().executeGet(frame), returnValue, firstMarkedContext);
                    throw CompilerDirectives.shouldNotReachHere();
                }
            }
            throw new CannotReturnToTarget(returnValue, getGetOrCreateContextNode().executeGet(frame));
        }

        /**
         * Walk the sender chain starting at the current Frame and terminating at homeContext.
         *
         * @return null if homeContext is not on sender chain; return first marked Context if found;
         *         raise NLR otherwise
         */
        @TruffleBoundary
        private static ContextObject firstUnwindMarkedOrThrowNLR(final ContextObject homeContext, final Object returnValue) {
            // Search the frames first.
            final ContextObject[] marked = new ContextObject[1];
            final ContextObject current = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<>() {
                ContextObject firstMarked = null;

                @Override
                public ContextObject visitFrame(final FrameInstance frameInstance) {
                    final Frame currentFrame = frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY);
                    // Exit on ResumingContextObject
                    if (!FrameAccess.isTruffleSqueakFrame(currentFrame)) {
                        final ContextObject resumingContext = FrameAccess.getResumingContextObjectOrSkip(frameInstance);
                        if (resumingContext == null) {
                            return null;
                        } else {
                            if (firstMarked == null && resumingContext.isUnwindMarked()) {
                                marked[0] = resumingContext;
                            }
                            return resumingContext;
                        }
                    }
                    // Exit if we find the homeContext.
                    final ContextObject context = FrameAccess.getContext(currentFrame);
                    if (context == homeContext) {
                        if (firstMarked == null) {
                            throw new NonLocalReturn(returnValue, homeContext);
                        }
                        return homeContext;
                    }
                    // Watch for marked ContextObjects.
                    if (firstMarked == null && context != null && context.isUnwindMarked()) {
                        marked[0] = firstMarked = context;
                    }
                    return null;
                }
            });
            if (current == null) {
                LogUtils.ITERATE_FRAMES.warning("firstUnwindMarkedOrThrowNLR did not find resumingContext!");
                return null;
            }
            ContextObject currentContext = current;
            ContextObject firstMarked = marked[0];

            // Continue searching through Contexts until we find either the homeContext or nil.
            while (currentContext != homeContext) {
                final AbstractSqueakObject sender = currentContext.getSender();
                if (sender == NilObject.SINGLETON) {
                    // cannot return
                    return null;
                } else {
                    currentContext = (ContextObject) sender;
                    if (firstMarked == null && currentContext.isUnwindMarked()) {
                        firstMarked = currentContext;
                    }
                }
            }
            if (firstMarked == null) {
                throw new NonLocalReturn(returnValue, homeContext);
            }
            return firstMarked;
        }

        private GetOrCreateContextNode getGetOrCreateContextNode() {
            if (getOrCreateContextNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getOrCreateContextNode = insert(GetOrCreateContextNode.create());
            }
            return getOrCreateContextNode;
        }

        private Dispatch2Node getSendAboutToReturnNode() {
            if (sendAboutToReturnNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                sendAboutToReturnNode = insert(Dispatch2NodeGen.create(getContext().aboutToReturnSelector));
            }
            return sendAboutToReturnNode;
        }

    }

    protected abstract static class AbstractReturnConstantNode extends AbstractNormalReturnNode {
        protected AbstractReturnConstantNode(final VirtualFrame frame, final CompiledCodeObject code, final int index) {
            super(frame, code, index);
        }

        @Override
        public final String toString() {
            CompilerAsserts.neverPartOfCompilation();
            return "return: " + getReturnValue(null).toString();
        }
    }

    public static final class ReturnConstantTrueNode extends AbstractReturnConstantNode {
        protected ReturnConstantTrueNode(final VirtualFrame frame, final CompiledCodeObject code, final int index) {
            super(frame, code, index);
        }

        @Override
        protected Object getReturnValue(final VirtualFrame frame) {
            return BooleanObject.TRUE;
        }
    }

    public static final class ReturnConstantFalseNode extends AbstractReturnConstantNode {
        protected ReturnConstantFalseNode(final VirtualFrame frame, final CompiledCodeObject code, final int index) {
            super(frame, code, index);
        }

        @Override
        protected Object getReturnValue(final VirtualFrame frame) {
            return BooleanObject.FALSE;
        }
    }

    public static final class ReturnConstantNilNode extends AbstractReturnConstantNode {
        protected ReturnConstantNilNode(final VirtualFrame frame, final CompiledCodeObject code, final int index) {
            super(frame, code, index);
        }

        @Override
        protected Object getReturnValue(final VirtualFrame frame) {
            return NilObject.SINGLETON;
        }
    }

    public static final class ReturnReceiverNode extends AbstractNormalReturnNode {
        protected ReturnReceiverNode(final VirtualFrame frame, final CompiledCodeObject code, final int index) {
            super(frame, code, index);
        }

        @Override
        protected Object getReturnValue(final VirtualFrame frame) {
            return FrameAccess.getReceiver(frame);
        }

        @Override
        public String toString() {
            CompilerAsserts.neverPartOfCompilation();
            return "returnSelf";
        }
    }

    public abstract static class AbstractBlockReturnNode extends AbstractReturnNode {

        /* Return to caller (never needs to unwind) */

        private final ConditionProfile hasModifiedSenderProfile = ConditionProfile.create();

        protected AbstractBlockReturnNode(final CompiledCodeObject code, final int index) {
            super(code, index);
        }

        @Override
        public final Object executeReturnSpecialized(final VirtualFrame frame) {
            if (hasModifiedSenderProfile.profile(FrameAccess.hasModifiedSender(frame))) {
                throw new NonVirtualReturn(getReturnValue(frame), FrameAccess.getSender(frame), null);
            } else {
                return getReturnValue(frame);
            }
        }
    }

    public static final class ReturnTopFromBlockNode extends AbstractBlockReturnNode {
        @Child private FrameStackPopNode popNode = FrameStackPopNode.create();

        protected ReturnTopFromBlockNode(final CompiledCodeObject code, final int index) {
            super(code, index);
        }

        @Override
        protected Object getReturnValue(final VirtualFrame frame) {
            return popNode.execute(frame);
        }

        @Override
        public String toString() {
            CompilerAsserts.neverPartOfCompilation();
            return "blockReturn";
        }
    }

    public static final class ReturnNilFromBlockNode extends AbstractBlockReturnNode {
        protected ReturnNilFromBlockNode(final CompiledCodeObject code, final int index) {
            super(code, index);
        }

        @Override
        protected Object getReturnValue(final VirtualFrame frame) {
            return NilObject.SINGLETON;
        }

        @Override
        public String toString() {
            CompilerAsserts.neverPartOfCompilation();
            return "blockReturn: nil";
        }
    }

    public static final class ReturnTopFromMethodNode extends AbstractNormalReturnNode {
        @Child private FrameStackPopNode popNode = FrameStackPopNode.create();

        protected ReturnTopFromMethodNode(final VirtualFrame frame, final CompiledCodeObject code, final int index) {
            super(frame, code, index);
        }

        @Override
        protected Object getReturnValue(final VirtualFrame frame) {
            return popNode.execute(frame);
        }

        @Override
        public String toString() {
            CompilerAsserts.neverPartOfCompilation();
            return "returnTop";
        }
    }
}
