/*
 * Copyright (c) 2017-2025 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021-2025 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.bytecodes;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.ConditionProfile;

import de.hpi.swa.trufflesqueak.exceptions.Returns.NonLocalReturn;
import de.hpi.swa.trufflesqueak.exceptions.Returns.NonVirtualReturn;
import de.hpi.swa.trufflesqueak.exceptions.SqueakExceptions.SqueakException;
import de.hpi.swa.trufflesqueak.image.SqueakImageContext;
import de.hpi.swa.trufflesqueak.model.AbstractSqueakObject;
import de.hpi.swa.trufflesqueak.model.BooleanObject;
import de.hpi.swa.trufflesqueak.model.CompiledCodeObject;
import de.hpi.swa.trufflesqueak.model.ContextObject;
import de.hpi.swa.trufflesqueak.model.FrameMarker;
import de.hpi.swa.trufflesqueak.model.NilObject;
import de.hpi.swa.trufflesqueak.nodes.AbstractNode;
import de.hpi.swa.trufflesqueak.nodes.context.frame.FrameStackPopNode;
import de.hpi.swa.trufflesqueak.nodes.context.frame.GetOrCreateContextNode;
import de.hpi.swa.trufflesqueak.nodes.dispatch.DispatchSelector2Node.Dispatch2Node;
import de.hpi.swa.trufflesqueak.util.ArrayUtils;
import de.hpi.swa.trufflesqueak.util.ContextUtils;
import de.hpi.swa.trufflesqueak.util.FrameAccess;
import de.hpi.swa.trufflesqueak.util.MiscUtils;

import static de.hpi.swa.trufflesqueak.nodes.bytecodes.SendBytecodes.sendCannotReturn;

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

        /* Return to sender */

        private final ConditionProfile hasModifiedSenderProfile = ConditionProfile.create();

        @Override
        protected Object execute(final VirtualFrame frame, final Object returnValue) {
            assert !FrameAccess.hasClosure(frame);
            if (hasModifiedSenderProfile.profile(FrameAccess.hasModifiedSender(frame))) {
                final Object senderOrNil = FrameAccess.getSender(frame);
                if (senderOrNil instanceof ContextObject sender) {
                    throw new NonVirtualReturn(returnValue, sender, FrameAccess.getContext(frame));
                } else if (senderOrNil == NilObject.SINGLETON) {
                    sendCannotReturn(this, frame, returnValue);
                    throw CompilerDirectives.shouldNotReachHere();
                }
            }
            return returnValue;
        }
    }

    private static final class ReturnFromClosureNode extends AbstractReturnKindNode {

        /* Return to closure's home context's sender */

        @Child private GetOrCreateContextNode getOrCreateContextNode;
        @Child private Dispatch2Node sendAboutToReturnNode;

        @Override
        protected Object execute(final VirtualFrame frame, final Object returnValue) {
            assert FrameAccess.hasClosure(frame);
            // Target is sender of closure's home context.
            final ContextObject homeContext = FrameAccess.getClosure(frame).getHomeContext();

            if (homeContext.canBeReturnedTo()) {
                /* Find the first marked context or homeContext, whichever occurs first. */
                final ContextObject stopContext = ContextUtils.findStopContext(frame, homeContext, this);

                /* Copy OpenSmalltalkVM: check if homeContext is marked -- perhaps overkill? */
                if (stopContext == homeContext && !homeContext.isUnwindMarkedNonClosure()) {
                    /* no unwind marked contexts */
                    throw new NonLocalReturn(returnValue, homeContext.getFrameSender());
                }

                /*
                 *  Can't use the unwind handling in NLR since sending value to the ensure block
                 *  could result in a process switch that would mess up the context stack unwind.
                 */
                if (stopContext != null) {          /* stopContext is unwind marked */
                    // send aboutToReturn:to:
                    getGetOrCreateSendAboutToReturnNode().execute(frame, getGetOrCreateContextNode().executeGet(frame), returnValue, stopContext);
                    throw CompilerDirectives.shouldNotReachHere();
                }
            }

            // We must return since the current process may continue.
            // It is likely that we need to handle this differently.
            sendCannotReturn(this, frame, returnValue);
            return returnValue;
        }

        private Dispatch2Node getGetOrCreateSendAboutToReturnNode() {
            if (sendAboutToReturnNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                sendAboutToReturnNode = insert(Dispatch2Node.create(SqueakImageContext.getSlow().aboutToReturnSelector));
            }
            return sendAboutToReturnNode;
        }

        private GetOrCreateContextNode getGetOrCreateContextNode() {
            if (getOrCreateContextNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getOrCreateContextNode = insert(GetOrCreateContextNode.create());
            }
            return getOrCreateContextNode;
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

        /* Return to caller */

        private final ConditionProfile hasModifiedSenderProfile = ConditionProfile.create();

        protected AbstractBlockReturnNode(final CompiledCodeObject code, final int index) {
            super(code, index);
        }

        @Override
        public final Object executeReturnSpecialized(final VirtualFrame frame) {
            if (hasModifiedSenderProfile.profile(FrameAccess.hasModifiedSender(frame))) {
                // Target is immediate sender.
                final Object senderOrNull = FrameAccess.getSender(frame);
                if ((senderOrNull instanceof final ContextObject returnContext) && returnContext.canBeReturnedTo()) {
                    throw new NonVirtualReturn(getReturnValue(frame), returnContext, FrameAccess.getContext(frame));
                }
                else {
                    sendCannotReturn(this, frame, getReturnValue(frame));
                    throw CompilerDirectives.shouldNotReachHere();
                }
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
