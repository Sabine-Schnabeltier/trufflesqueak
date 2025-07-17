/*
 * Copyright (c) 2017-2025 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021-2025 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.bytecodes;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.ConditionProfile;

import de.hpi.swa.trufflesqueak.exceptions.ProcessSwitch;
import de.hpi.swa.trufflesqueak.exceptions.Returns.NonLocalReturn;
import de.hpi.swa.trufflesqueak.exceptions.SqueakExceptions.SqueakException;
import de.hpi.swa.trufflesqueak.image.SqueakImageContext;
import de.hpi.swa.trufflesqueak.model.BooleanObject;
import de.hpi.swa.trufflesqueak.model.CompiledCodeObject;
import de.hpi.swa.trufflesqueak.model.ContextObject;
import de.hpi.swa.trufflesqueak.model.NilObject;
import de.hpi.swa.trufflesqueak.model.layout.ObjectLayouts;
import de.hpi.swa.trufflesqueak.nodes.AbstractNode;
import de.hpi.swa.trufflesqueak.nodes.accessing.AbstractPointersObjectNodes;
import de.hpi.swa.trufflesqueak.nodes.accessing.AbstractPointersObjectNodes.AbstractPointersObjectWriteNode;
import de.hpi.swa.trufflesqueak.nodes.context.frame.FrameStackPopNode;
import de.hpi.swa.trufflesqueak.nodes.context.frame.FrameStackPushNode;
import de.hpi.swa.trufflesqueak.nodes.context.frame.GetOrCreateContextNode;
import de.hpi.swa.trufflesqueak.util.FrameAccess;
import de.hpi.swa.trufflesqueak.util.LogUtils;

import java.util.logging.Level;

public final class ReturnBytecodes {

    public abstract static class AbstractReturnNode extends AbstractBytecodeNode {
        protected final int thisIndex;

        protected AbstractReturnNode(final CompiledCodeObject code, final int index) {
            super(code, index);
            thisIndex = code.getInitialPC() + index;
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

        protected final int getThisIndex() { return thisIndex; }
    }

    public abstract static class AbstractNormalReturnNode extends AbstractReturnNode {
        @Child private AbstractReturnKindNode returnNode;

        protected AbstractNormalReturnNode(final VirtualFrame frame, final CompiledCodeObject code, final int index) {
            super(code, index);
            returnNode = FrameAccess.hasClosure(frame) ? new ReturnFromClosureNode() : new ReturnFromMethodNode();
        }

        @Override
        public final Object executeReturnSpecialized(final VirtualFrame frame) {
            return returnNode.execute(frame, this);
        }
    }

    private abstract static class AbstractReturnKindNode extends AbstractNode {
        protected abstract Object execute(VirtualFrame frame, AbstractNormalReturnNode returnNode);
    }

    private static final class ReturnFromMethodNode extends AbstractReturnKindNode {

        /* Return to sender (never needs to unwind) */

        private final ConditionProfile hasModifiedSenderProfile = ConditionProfile.create();

        @Override
        protected Object execute(final VirtualFrame frame, final AbstractNormalReturnNode returnNode) {
            assert !FrameAccess.hasClosure(frame);
            if (hasModifiedSenderProfile.profile(FrameAccess.hasModifiedSender(frame))) {
                assert FrameAccess.getSender(frame) instanceof ContextObject : "Sender must be a materialized ContextObject";
                LogUtils.SCHEDULING.log(Level.FINE, "ReturnFromMethodNode with modified sender: {0}", FrameAccess.getSender(frame));
                // Suspend current context and throw ProcessSwitch to unwind Java stack and resume
                FrameAccess.setInstructionPointer(frame, returnNode.getThisIndex());
                final ContextObject activeContext = GetOrCreateContextNode.getOrCreateUncached(frame);
                AbstractPointersObjectWriteNode.executeUncached(getContext().getActiveProcessSlow(), ObjectLayouts.PROCESS.SUSPENDED_CONTEXT, activeContext);
                throw ProcessSwitch.SINGLETON;
//                throw new NonLocalReturn(returnValue, FrameAccess.getSender(frame));
            } else {
                return returnNode.getReturnValue(frame);
            }
        }
    }

    private static final class ReturnFromClosureNode extends AbstractReturnKindNode {

        /* Return to closure's home context's sender, executing unwind blocks */

        @Override
        protected Object execute(final VirtualFrame frame, final AbstractNormalReturnNode returnNode) {
            assert FrameAccess.hasClosure(frame);
            final Object returnValue = returnNode.getReturnValue(frame);
            // Target is sender of closure's home context.
            final ContextObject homeContext = FrameAccess.getClosure(frame).getHomeContext();
            if (homeContext.canBeReturnedTo()) {
                throw new NonLocalReturn(returnValue, homeContext.getFrameSender());
            } else {
                CompilerDirectives.transferToInterpreter();
                final ContextObject contextObject = GetOrCreateContextNode.getOrCreateUncached(frame);
                final SqueakImageContext image = getContext();
                image.cannotReturn.executeAsSymbolSlow(image, frame, contextObject, returnValue);
                throw CompilerDirectives.shouldNotReachHere();
            }
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
                LogUtils.SCHEDULING.log(Level.FINE, "AbstractBlockReturnNode with modified sender: {0}", FrameAccess.getSender(frame));
                // Suspend current context and throw ProcessSwitch to unwind Java stack and resume
                FrameAccess.setInstructionPointer(frame, getThisIndex());
                final ContextObject activeContext = GetOrCreateContextNode.getOrCreateUncached(frame);
                AbstractPointersObjectWriteNode.executeUncached(getContext().getActiveProcessSlow(), ObjectLayouts.PROCESS.SUSPENDED_CONTEXT, activeContext);
                throw ProcessSwitch.SINGLETON;

/*                // Target is sender of closure's home context.
                final ContextObject homeContext = FrameAccess.getClosure(frame).getHomeContext();
                if (homeContext.canBeReturnedTo()) {
                    throw new NonLocalReturn(getReturnValue(frame), homeContext.getFrameSender());
                } else {
                    CompilerDirectives.transferToInterpreter();
                    final ContextObject contextObject = GetOrCreateContextNode.getOrCreateUncached(frame);
                    final SqueakImageContext image = getContext();
                    image.cannotReturn.executeAsSymbolSlow(image, frame, contextObject, getReturnValue(frame));
                    throw CompilerDirectives.shouldNotReachHere();
                }
*/            } else {
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
