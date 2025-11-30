/*
 * Copyright (c) 2017-2025 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021-2025 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.util;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameDescriptor.Builder;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

import de.hpi.swa.trufflesqueak.exceptions.SqueakExceptions.SqueakException;
import de.hpi.swa.trufflesqueak.model.AbstractSqueakObject;
import de.hpi.swa.trufflesqueak.model.ArrayObject;
import de.hpi.swa.trufflesqueak.model.BlockClosureObject;
import de.hpi.swa.trufflesqueak.model.CompiledCodeObject;
import de.hpi.swa.trufflesqueak.model.ContextObject;
import de.hpi.swa.trufflesqueak.model.NativeObject;
import de.hpi.swa.trufflesqueak.model.NilObject;
import de.hpi.swa.trufflesqueak.model.PointersObject;
import de.hpi.swa.trufflesqueak.nodes.ResumeContextRootNode;
import de.hpi.swa.trufflesqueak.nodes.context.GetOrCreateContextWithoutFrameNode;

/**
 * TruffleSqueak frame argument layout.
 *
 * <pre>
 *                            +---------------------------------+
 * SENDER                  -> | ContextObject                   |
 *                            | nil (end of sender chain)       |
 *                            +---------------------------------+
 * CLOSURE_OR_NULL         -> | BlockClosure / null / OSR Frame |
 *                            +---------------------------------+
 * RECEIVER                -> | Object                          |
 *                            +---------------------------------+
 * ARGUMENTS_START         -> | argument0                       |
 *                            | argument1                       |
 *                            | ...                             |
 *                            | argument(nArgs-1)               |
 *                            | copiedValue1                    |
 *                            | copiedValue2                    |
 *                            | ...                             |
 *                            | copiedValue(nCopied-1)          |
 *                            +---------------------------------+
 * </pre>
 *
 * TruffleSqueak frame slot layout.
 *
 * <pre>
 *                       +-------------------------------+
 * thisContext        -> | ContextObject                 |
 *                       +-------------------------------+
 * instructionPointer -> | int (< 0 if terminated        |
 *                       +-------------------------------+
 * stackPointer       -> | int                           |
 *                       +-------------------------------+
 * stackSlots         -> | Object[] (specialized)        |
 *                       +-------------------------------+
 * </pre>
 */
public final class FrameAccess {
    private enum ArgumentIndicies {
        SENDER, // 0
        CLOSURE_OR_NULL, // 1
        RECEIVER, // 2
        ARGUMENTS_START, // 3
    }

    private enum SlotIndicies {
        THIS_CONTEXT,
        INSTRUCTION_POINTER,
        STACK_POINTER,
        STACK_START,
    }

    private static final int SENDER_ARG_INDEX = ArgumentIndicies.SENDER.ordinal();
    private static final int CLOSURE_OR_NULL_ARG_INDEX = ArgumentIndicies.CLOSURE_OR_NULL.ordinal();
    private static final int RECEIVER_ARG_INDEX = ArgumentIndicies.RECEIVER.ordinal();
    private static final int ARGUMENTS_START_ARG_INDEX = ArgumentIndicies.ARGUMENTS_START.ordinal();

    private static final int THIS_CONTEXT_SLOT_INDEX = SlotIndicies.THIS_CONTEXT.ordinal();
    private static final int INSTRUCTION_POINTER_SLOT_INDEX = SlotIndicies.INSTRUCTION_POINTER.ordinal();
    private static final int STACK_POINTER_SLOT_INDEX = SlotIndicies.STACK_POINTER.ordinal();
    private static final int STACK_START_SLOT_INDEX = SlotIndicies.STACK_START.ordinal();

    private FrameAccess() {
    }

    /** Creates a new {@link FrameDescriptor} according to {@link SlotIndicies}. */
    public static FrameDescriptor newFrameDescriptor(final CompiledCodeObject code) {
        final int numStackSlots = code.getMaxNumStackSlots();
        final Builder builder = FrameDescriptor.newBuilder(4 + numStackSlots);
        builder.info(code);
        addDefaultSlots(builder);
        builder.addSlots(numStackSlots, FrameSlotKind.Static);
        return builder.build();
    }

    public static VirtualFrame newDummyFrame(final CompiledCodeObject dummyMethod) {
        final Builder builder = FrameDescriptor.newBuilder(4);
        builder.info(dummyMethod);
        addDefaultSlots(builder);
        return Truffle.getRuntime().createVirtualFrame(FrameAccess.newWith(NilObject.SINGLETON, null, NilObject.SINGLETON), builder.build());
    }

    private static void addDefaultSlots(final Builder builder) {
        builder.addSlot(FrameSlotKind.Static, null, null); // SlotIndicies.THIS_CONTEXT
        builder.addSlot(FrameSlotKind.Static, null, null); // SlotIndicies.INSTRUCTION_POINTER
        builder.addSlot(FrameSlotKind.Static, null, null); // SlotIndicies.STACK_POINTER
    }

    public static void copyAllSlots(final MaterializedFrame source, final MaterializedFrame destination) {
        source.copyTo(0, destination, 0, source.getFrameDescriptor().getNumberOfSlots());
    }

    /* Returns the code object matching the frame's descriptor. */
    public static CompiledCodeObject getCodeObject(final Frame frame) {
        return (CompiledCodeObject) frame.getFrameDescriptor().getInfo();
    }

    public static AbstractSqueakObject getSender(final Frame frame) {
        return (AbstractSqueakObject) frame.getArguments()[SENDER_ARG_INDEX];
    }

    public static ContextObject getSenderContext(final Frame frame) {
        return (ContextObject) getSender(frame);
    }

    public static void setSender(final Frame frame, final AbstractSqueakObject value) {
        frame.getArguments()[SENDER_ARG_INDEX] = value;
    }

    public static BlockClosureObject getClosure(final Frame frame) {
        return (BlockClosureObject) frame.getArguments()[CLOSURE_OR_NULL_ARG_INDEX];
    }

    public static boolean hasClosure(final Frame frame) {
        return frame.getArguments()[CLOSURE_OR_NULL_ARG_INDEX] instanceof BlockClosureObject;
    }

    public static void setClosure(final Frame frame, final BlockClosureObject closure) {
        frame.getArguments()[CLOSURE_OR_NULL_ARG_INDEX] = closure;
    }

    public static Object[] storeParentFrameInArguments(final VirtualFrame parentFrame) {
        assert !hasClosure(parentFrame);
        final Object[] arguments = parentFrame.getArguments();
        arguments[CLOSURE_OR_NULL_ARG_INDEX] = parentFrame;
        return arguments;
    }

    public static Frame restoreParentFrameFromArguments(final Object[] arguments) {
        final Object frame = arguments[CLOSURE_OR_NULL_ARG_INDEX];
        arguments[CLOSURE_OR_NULL_ARG_INDEX] = null;
        return (Frame) frame;
    }

    public static Object getReceiver(final Frame frame) {
        return frame.getArguments()[RECEIVER_ARG_INDEX];
    }

    public static void setReceiver(final Frame frame, final Object receiver) {
        frame.getArguments()[RECEIVER_ARG_INDEX] = receiver;
    }

    public static Object getArgument(final Frame frame, final int index) {
        return frame.getArguments()[RECEIVER_ARG_INDEX + index];
    }

    public static int getReceiverStartIndex() {
        return RECEIVER_ARG_INDEX;
    }

    public static int getArgumentStartIndex() {
        return ARGUMENTS_START_ARG_INDEX;
    }

    public static int getNumArguments(final Frame frame) {
        return frame.getArguments().length - getArgumentStartIndex();
    }

    public static Object[] getReceiverAndArguments(final Frame frame) {
        CompilerAsserts.neverPartOfCompilation();
        return Arrays.copyOfRange(frame.getArguments(), getReceiverStartIndex(), frame.getArguments().length);
    }

    public static ContextObject getContext(final Frame frame) {
        return (ContextObject) frame.getObjectStatic(THIS_CONTEXT_SLOT_INDEX);
    }

    public static boolean hasModifiedSender(final VirtualFrame frame) {
        final ContextObject context = getContext(frame);
        return context != null && context.hasModifiedSender();
    }

    public static void setContext(final Frame frame, final ContextObject context) {
        assert getContext(frame) == null : "ContextObject already allocated";
        frame.setObjectStatic(THIS_CONTEXT_SLOT_INDEX, context);
    }

    public static int getInstructionPointer(final Frame frame) {
        return frame.getIntStatic(INSTRUCTION_POINTER_SLOT_INDEX);
    }

    public static boolean isDead(final Frame frame) {
        return getInstructionPointer(frame) < ContextObject.NIL_PC_THRESHOLD;
    }

    public static void setInstructionPointer(final Frame frame, final int value) {
        frame.setIntStatic(INSTRUCTION_POINTER_SLOT_INDEX, value);
    }

    public static int getStackPointer(final Frame frame) {
        return frame.getIntStatic(STACK_POINTER_SLOT_INDEX);
    }

    @NeverDefault
    public static int getIncrementedStackPointer(final VirtualFrame frame) {
        return getStackPointer(frame) + 1;
    }

    public static void setStackPointer(final Frame frame, final int value) {
        frame.setIntStatic(STACK_POINTER_SLOT_INDEX, value);
    }

    public static int toStackSlotIndex(final Frame frame, final int index) {
        assert frame.getArguments().length - getArgumentStartIndex() <= index;
        return STACK_START_SLOT_INDEX + index;
    }

    public static int toStackSlotIndex(final int index) {
        return STACK_START_SLOT_INDEX + index;
    }

    public static Object getStackValue(final Frame frame, final int stackIndex, final int numArguments) {
        if (stackIndex < numArguments) {
            return getArgument(frame, stackIndex);
        } else {
            return getSlotValue(frame, toStackSlotIndex(frame, stackIndex));
        }
    }

    public static int getNumStackSlots(final Frame frame) {
        return frame.getFrameDescriptor().getNumberOfSlots() - STACK_START_SLOT_INDEX;
    }

    public static boolean slotsAreNotNilled(final Frame frame) {
        return getInstructionPointer(frame) == ContextObject.NIL_PC_STACK_NOT_NIL_VALUE;
    }

    public static void setSlotsAreNilled(final Frame frame) {
        setInstructionPointer(frame, ContextObject.NIL_PC_STACK_NIL_VALUE);
    }

    /**
     * The stack of a dead frame is unreachable. Nil it out here.
     * <p>
     * From Squeak6.0-22104 class comment for Context - eem 4/4/2017 17:45
     * <p>
     * "Contexts refer to the context in which they were created via the sender inst var. An
     * execution stack is made up of a linked list of contexts, linked through their sender inst
     * var. Returning involves returning back to the sender. When a context is returned from, its
     * sender and pc are nilled, and, if the context is still referred to, the virtual machine
     * guarantees to preserve only the arguments after a return."
     */
    private static void clearStackSlots(final Frame frame) {
        assert isDead(frame);
        /*
         * Replace all initialized stack slots with null, removing spurious references from the
         * stack.
         */
        if (slotsAreNotNilled(frame)) {
            setSlotsAreNilled(frame);
            final FrameDescriptor frameDescriptor = frame.getFrameDescriptor();
            for (int slotIndex = STACK_START_SLOT_INDEX; slotIndex < frameDescriptor.getNumberOfSlots(); slotIndex++) {
                frame.setObjectStatic(slotIndex, null);
            }
            for (int auxSlotIndex = 0; auxSlotIndex < frameDescriptor.getNumberOfAuxiliarySlots(); auxSlotIndex++) {
                frame.setAuxiliarySlot(auxSlotIndex, null);
            }
        }
    }

    /* Iterates used stack slots (may not be ordered). The stack of a dead frame is unreachable. */
    public static void iterateStackSlots(final Frame frame, final Consumer<Integer> action) {
        if (isDead(frame)) {
            clearStackSlots(frame);
        } else {
            for (int slotIndex = STACK_START_SLOT_INDEX; slotIndex < frame.getFrameDescriptor().getNumberOfSlots(); slotIndex++) {
                action.accept(slotIndex);
            }
        }
    }

    /* Iterate stack objects (may not be ordered). The stack of a dead frame is unreachable. */
    public static void iterateStackObjects(final Frame frame, final boolean clearStackSlots, final Consumer<Object> action) {
        if (isDead(frame)) {
            if (clearStackSlots) {
                clearStackSlots(frame);
            }
        } else {
            final FrameDescriptor frameDescriptor = frame.getFrameDescriptor();
            for (int slotIndex = STACK_START_SLOT_INDEX; slotIndex < frameDescriptor.getNumberOfSlots(); slotIndex++) {
                action.accept(frame.getObjectStatic(slotIndex));
            }
            for (int auxSlotIndex = 0; auxSlotIndex < frameDescriptor.getNumberOfAuxiliarySlots(); auxSlotIndex++) {
                action.accept(frame.getAuxiliarySlot(auxSlotIndex));
            }
        }
    }

    /*
     * Iterate stack objects (may not be ordered) with optional replacement. The stack of a dead
     * frame is unreachable.
     */
    public static void iterateStackObjectsWithReplacement(final Frame frame, final boolean clearStackSlots, final Function<Object, Object> action) {
        if (isDead(frame)) {
            if (clearStackSlots) {
                clearStackSlots(frame);
            }
        } else {
            final FrameDescriptor frameDescriptor = frame.getFrameDescriptor();
            for (int slotIndex = STACK_START_SLOT_INDEX; slotIndex < frameDescriptor.getNumberOfSlots(); slotIndex++) {
                final Object replacement = action.apply(frame.getObjectStatic(slotIndex));
                if (replacement != null) {
                    frame.setObjectStatic(slotIndex, replacement);
                }
            }
            for (int auxSlotIndex = 0; auxSlotIndex < frameDescriptor.getNumberOfAuxiliarySlots(); auxSlotIndex++) {
                final Object replacement = action.apply(frame.getAuxiliarySlot(auxSlotIndex));
                if (replacement != null) {
                    frame.setAuxiliarySlot(auxSlotIndex, replacement);
                }
            }
        }
    }

    public static Object getSlotValue(final Frame frame, final int slotIndex) {
        try {
            return frame.getObjectStatic(slotIndex);
        } catch (final ArrayIndexOutOfBoundsException aioobe) {
            CompilerDirectives.transferToInterpreter();
            final int auxSlotIndex = frame.getFrameDescriptor().findOrAddAuxiliarySlot(slotIndex);
            return frame.getAuxiliarySlot(auxSlotIndex);
        }
    }

    public static void setSlotValue(final Frame frame, final int slotIndex, final Object value) {
        try {
            frame.setObjectStatic(slotIndex, value);
        } catch (final ArrayIndexOutOfBoundsException aioobe) {
            CompilerDirectives.transferToInterpreter();
            final int auxSlotIndex = frame.getFrameDescriptor().findOrAddAuxiliarySlot(slotIndex);
            frame.setAuxiliarySlot(auxSlotIndex, value);
        }
    }

    public static void setStackSlot(final Frame frame, final int stackIndex, final Object value) {
        assert value != null;
        assert 0 <= stackIndex && stackIndex <= getCodeObject(frame).getSqueakContextSize();
        setSlotValue(frame, STACK_START_SLOT_INDEX + stackIndex, value);
    }

    public static boolean hasUnusedAuxiliarySlots(final Frame frame) {
        for (final int slotIndex : frame.getFrameDescriptor().getAuxiliarySlots().values()) {
            if (frame.getAuxiliarySlot(slotIndex) != null) {
                return false;
            }
        }
        return true;
    }

    public static void terminateFrame(final Frame frame) {
        setInstructionPointer(frame, ContextObject.NIL_PC_STACK_NOT_NIL_VALUE);
        setSender(frame, NilObject.SINGLETON);
    }

    public static boolean isTruffleSqueakFrame(final Frame frame) {
        return frame.getArguments().length >= RECEIVER_ARG_INDEX && frame.getFrameDescriptor().getInfo() instanceof CompiledCodeObject;
    }

    public static Object[] newWith(final AbstractSqueakObject sender, final BlockClosureObject closure, final Object[] receiverAndArguments) {
        final int receiverAndArgumentsLength = receiverAndArguments.length;
        final Object[] frameArguments = new Object[RECEIVER_ARG_INDEX + receiverAndArgumentsLength];
        assert sender != null : "Sender should never be null";
        assert receiverAndArgumentsLength > 0 : "At least a receiver must be provided";
        assert receiverAndArguments[0] != null : "Receiver should never be null";
        frameArguments[SENDER_ARG_INDEX] = sender;
        frameArguments[CLOSURE_OR_NULL_ARG_INDEX] = closure;
        ArrayUtils.arraycopy(receiverAndArguments, 0, frameArguments, RECEIVER_ARG_INDEX, receiverAndArgumentsLength);
        return frameArguments;
    }

    public static Object[] newWith(final AbstractSqueakObject sender, final BlockClosureObject closure, final Object receiver) {
        assert sender != null && receiver != null : "Sender and receiver should never be null";
        final Object[] frameArguments = new Object[ARGUMENTS_START_ARG_INDEX];
        frameArguments[SENDER_ARG_INDEX] = sender;
        frameArguments[CLOSURE_OR_NULL_ARG_INDEX] = closure;
        frameArguments[RECEIVER_ARG_INDEX] = receiver;
        return frameArguments;
    }

    public static Object[] newWith(final AbstractSqueakObject sender, final BlockClosureObject closure, final Object receiver, final Object arg1) {
        assert sender != null && receiver != null : "Sender and receiver should never be null";
        final Object[] frameArguments = new Object[ARGUMENTS_START_ARG_INDEX + 1];
        frameArguments[SENDER_ARG_INDEX] = sender;
        frameArguments[CLOSURE_OR_NULL_ARG_INDEX] = closure;
        frameArguments[RECEIVER_ARG_INDEX] = receiver;
        frameArguments[ARGUMENTS_START_ARG_INDEX] = arg1;
        return frameArguments;
    }

    public static Object[] newWith(final AbstractSqueakObject sender, final BlockClosureObject closure, final Object receiver, final Object arg1, final Object arg2) {
        assert sender != null && receiver != null : "Sender and receiver should never be null";
        final Object[] frameArguments = new Object[ARGUMENTS_START_ARG_INDEX + 2];
        frameArguments[SENDER_ARG_INDEX] = sender;
        frameArguments[CLOSURE_OR_NULL_ARG_INDEX] = closure;
        frameArguments[RECEIVER_ARG_INDEX] = receiver;
        frameArguments[ARGUMENTS_START_ARG_INDEX] = arg1;
        frameArguments[ARGUMENTS_START_ARG_INDEX + 1] = arg2;
        return frameArguments;
    }

    public static Object[] newWith(final AbstractSqueakObject sender, final BlockClosureObject closure, final Object receiver, final Object arg1, final Object arg2, final Object arg3) {
        assert sender != null && receiver != null : "Sender and receiver should never be null";
        final Object[] frameArguments = new Object[ARGUMENTS_START_ARG_INDEX + 3];
        frameArguments[SENDER_ARG_INDEX] = sender;
        frameArguments[CLOSURE_OR_NULL_ARG_INDEX] = closure;
        frameArguments[RECEIVER_ARG_INDEX] = receiver;
        frameArguments[ARGUMENTS_START_ARG_INDEX] = arg1;
        frameArguments[ARGUMENTS_START_ARG_INDEX + 1] = arg2;
        frameArguments[ARGUMENTS_START_ARG_INDEX + 2] = arg3;
        return frameArguments;
    }

    public static Object[] newWith(final AbstractSqueakObject sender, final BlockClosureObject closure, final Object receiver, final Object arg1, final Object arg2, final Object arg3,
                    final Object arg4) {
        assert sender != null && receiver != null : "Sender and receiver should never be null";
        final Object[] frameArguments = new Object[ARGUMENTS_START_ARG_INDEX + 4];
        frameArguments[SENDER_ARG_INDEX] = sender;
        frameArguments[CLOSURE_OR_NULL_ARG_INDEX] = closure;
        frameArguments[RECEIVER_ARG_INDEX] = receiver;
        frameArguments[ARGUMENTS_START_ARG_INDEX] = arg1;
        frameArguments[ARGUMENTS_START_ARG_INDEX + 1] = arg2;
        frameArguments[ARGUMENTS_START_ARG_INDEX + 2] = arg3;
        frameArguments[ARGUMENTS_START_ARG_INDEX + 3] = arg4;
        return frameArguments;
    }

    public static Object[] newWith(final AbstractSqueakObject sender, final BlockClosureObject closure, final Object receiver, final Object arg1, final Object arg2, final Object arg3,
                    final Object arg4, final Object arg5) {
        assert sender != null && receiver != null : "Sender and receiver should never be null";
        final Object[] frameArguments = new Object[ARGUMENTS_START_ARG_INDEX + 5];
        frameArguments[SENDER_ARG_INDEX] = sender;
        frameArguments[CLOSURE_OR_NULL_ARG_INDEX] = closure;
        frameArguments[RECEIVER_ARG_INDEX] = receiver;
        frameArguments[ARGUMENTS_START_ARG_INDEX] = arg1;
        frameArguments[ARGUMENTS_START_ARG_INDEX + 1] = arg2;
        frameArguments[ARGUMENTS_START_ARG_INDEX + 2] = arg3;
        frameArguments[ARGUMENTS_START_ARG_INDEX + 3] = arg4;
        frameArguments[ARGUMENTS_START_ARG_INDEX + 4] = arg5;
        return frameArguments;
    }

    public static Object[] newWith(final AbstractSqueakObject sender, final BlockClosureObject closure, final Object receiver, final Object[] arguments) {
        assert sender != null && receiver != null : "Sender and receiver should never be null";
        final Object[] frameArguments = new Object[ARGUMENTS_START_ARG_INDEX + arguments.length];
        frameArguments[SENDER_ARG_INDEX] = sender;
        frameArguments[CLOSURE_OR_NULL_ARG_INDEX] = closure;
        frameArguments[RECEIVER_ARG_INDEX] = receiver;
        ArrayUtils.arraycopy(arguments, 0, frameArguments, ARGUMENTS_START_ARG_INDEX, arguments.length);
        return frameArguments;
    }

    public static Object[] newWith(final int numArgs) {
        final Object[] frameArguments = new Object[ARGUMENTS_START_ARG_INDEX + numArgs];
        frameArguments[SENDER_ARG_INDEX] = NilObject.SINGLETON;
        return frameArguments;
    }

    public static Object[] newDNUWith(final AbstractSqueakObject sender, final Object receiver, final PointersObject message) {
        return newWith(sender, null, receiver, message);
    }

    public static Object[] newOAMWith(final AbstractSqueakObject sender, final Object object, final NativeObject selector, final ArrayObject arguments, final Object receiver) {
        return newWith(sender, null, object, selector, arguments, receiver);
    }

    /* Template because closure arguments still need to be filled in. */
    public static Object[] newClosureArgumentsTemplate(final BlockClosureObject closure, final ContextObject sender, final int numArgs) {
        final Object[] copied = closure.getCopiedValues();
        final int numCopied = copied.length;
        assert closure.getNumArgs() == numArgs : "number of required and provided block arguments do not match";
        final Object[] arguments = new Object[ARGUMENTS_START_ARG_INDEX + numArgs + numCopied];
        arguments[SENDER_ARG_INDEX] = sender;
        arguments[CLOSURE_OR_NULL_ARG_INDEX] = closure;
        arguments[RECEIVER_ARG_INDEX] = closure.getReceiver();
        assert arguments[RECEIVER_ARG_INDEX] != null;
        ArrayUtils.arraycopy(copied, 0, arguments, ARGUMENTS_START_ARG_INDEX + numArgs, numCopied);
        return arguments;
    }

    public static Object[] createFrameArguments(final VirtualFrame frame, final BlockClosureObject closure, final GetOrCreateContextWithoutFrameNode getOrCreateContextWithoutFrameNode) {
        return FrameAccess.newClosureArgumentsTemplate(closure, getOrCreateContextWithoutFrameNode.execute(frame), 0);
    }

    public static Object[] createFrameArguments(final VirtualFrame frame, final BlockClosureObject closure, final GetOrCreateContextWithoutFrameNode getOrCreateContextWithoutFrameNode,
                    final Object arg1) {
        final Object[] frameArguments = FrameAccess.newClosureArgumentsTemplate(closure, getOrCreateContextWithoutFrameNode.execute(frame), 1);
        frameArguments[FrameAccess.getArgumentStartIndex()] = arg1;
        return frameArguments;
    }

    public static int expectedArgumentSize(final int numArgsAndCopied) {
        return ARGUMENTS_START_ARG_INDEX + numArgsAndCopied;
    }

    public static void assertSenderNotNull(final Frame frame) {
        assert getSender(frame) != null : "Sender should not be null";
    }

    public static void assertReceiverNotNull(final Frame frame) {
        assert getReceiver(frame) != null : "Receiver should not be null";
    }

    public static ContextObject getResumingContextObjectOrSkip(final FrameInstance frameInstance) {
        if (frameInstance.getCallTarget() instanceof final RootCallTarget rct) {
            if (rct.getRootNode() instanceof final ResumeContextRootNode rcrn) {
                /*
                 * Reached end of Smalltalk activations on Truffle frames. From here, tracing should
                 * continue to walk senders via ContextObjects.
                 */
                return rcrn.getActiveContext(); // break
            }
            /* Work around OSR bug. */
            for (final Node child : rct.getRootNode().getChildren()) {
                /* In case of OSR, the first child node is the actual root node */
                if (child instanceof final ResumeContextRootNode rcrn) {
                    LogUtils.ITERATE_FRAMES.warning("Workaround for OSR bug taken");
                    return rcrn.getActiveContext();
                }
            }
            return null; // skip
        } else {
            return null; // skip
        }
    }

    @TruffleBoundary
    public static MaterializedFrame findFrameForContext(final ContextObject context) {
        CompilerDirectives.bailout("Finding materializable frames should never be part of compiled code as it triggers deopts");
        LogUtils.ITERATE_FRAMES.fine("Iterating frames to find a ContextObject...");
        final Object frameOrResumingContextObject = Truffle.getRuntime().iterateFrames(frameInstance -> {
            final Frame current = frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY);
            if (!isTruffleSqueakFrame(current)) {
                return FrameAccess.getResumingContextObjectOrSkip(frameInstance);
            }
            LogUtils.ITERATE_FRAMES.fine(() -> "..." + FrameAccess.getCodeObject(current).toString());
            final ContextObject contextObject = getContext(current);
            if (context == contextObject) {
                assert contextObject == null || !contextObject.hasTruffleFrame() : "Redundant frame lookup";
                return frameInstance.getFrame(FrameInstance.FrameAccess.MATERIALIZE).materialize();
            } else {
                return null; // continue with next frame
            }
        });
        if (frameOrResumingContextObject instanceof final MaterializedFrame materializedFrame) {
            return materializedFrame;
        } else if (frameOrResumingContextObject instanceof final ContextObject resumingContextObject) {
            ContextObject currentContext = resumingContextObject;
            while (true) {
                if (currentContext == context) {
                    return currentContext.getTruffleFrame(); // success
                }
                final AbstractSqueakObject sender = currentContext.getMaterializedSender();
                if (sender instanceof final ContextObject senderContext) {
                    currentContext = senderContext;
                } else { // end of chain
                    assert sender == NilObject.SINGLETON;
                    break; // fail
                }
            }
        }
        throw SqueakException.create("Could not find frame for:", context);
    }
}
