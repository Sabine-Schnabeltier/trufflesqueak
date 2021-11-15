/*
 * Copyright (c) 2017-2021 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.context.frame;

import java.util.Objects;

import org.graalvm.collections.EconomicMap;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.FrameUtil;
import com.oracle.truffle.api.nodes.Node;

import de.hpi.swa.trufflesqueak.model.BlockClosureObject;
import de.hpi.swa.trufflesqueak.nodes.AbstractNode;
import de.hpi.swa.trufflesqueak.nodes.context.frame.FrameStackReadNodeFactory.FrameSlotReadClearNodeGen;
import de.hpi.swa.trufflesqueak.nodes.context.frame.FrameStackReadNodeFactory.FrameSlotReadNoClearNodeGen;
import de.hpi.swa.trufflesqueak.util.FrameAccess;

@ImportStatic(FrameSlotKind.class)
public abstract class FrameStackReadNode extends AbstractNode {

    public static final FrameStackReadNode create(final Frame frame, final int index, final boolean clear) {
        final int numArgs = FrameAccess.getNumArguments(frame);
        if (index < numArgs) {
            return FrameArgumentNode.getOrCreate(index);
        }
        // Only clear stack values, not receiver, arguments, or temporary variables.
        final int initialSP;
        final BlockClosureObject closure = FrameAccess.getClosure(frame);
        if (closure == null) {
            initialSP = FrameAccess.getCodeObject(frame).getNumTemps();
            assert numArgs == FrameAccess.getCodeObject(frame).getNumArgs();
        } else {
            initialSP = closure.getNumTemps();
            assert numArgs == closure.getNumArgs() + closure.getNumCopied();
        }
        assert initialSP >= numArgs;
        final FrameSlot slot = Objects.requireNonNull(FrameAccess.findStackSlot(frame, index));
        if (clear && index >= initialSP) { // only ever clear non-temp slots
            return FrameSlotReadClearNodeGen.create(slot);
        } else {
            return FrameSlotReadNoClearNodeGen.create(slot);
        }
    }

    public static final FrameStackReadNode createTemporaryReadNode(final Frame frame, final int index) {
        final int numArgs = FrameAccess.getNumArguments(frame);
        if (index < numArgs) {
            return FrameArgumentNode.getOrCreate(index);
        } else {
            return FrameSlotReadNoClearNodeGen.create(FrameAccess.findStackSlot(frame, index));
        }
    }

    public final Object executeRead(final Frame frame) {
        final Object value = executeReadUnsafe(frame);
        assert value != null : "Unexpected `null` value";
        return value;
    }

    /* Unsafe as it may return `null` values. */
    public abstract Object executeReadUnsafe(Frame frame);

    @NodeField(name = "slot", type = FrameSlot.class)
    protected abstract static class AbstractFrameSlotReadNode extends FrameStackReadNode {

        protected abstract FrameSlot getSlot();

        @Specialization(guards = "frame.isBoolean(getSlot())")
        protected final boolean readBoolean(final Frame frame) {
            return FrameUtil.getBooleanSafe(frame, getSlot());
        }

        @Specialization(guards = "frame.isLong(getSlot())")
        protected final long readLong(final Frame frame) {
            return FrameUtil.getLongSafe(frame, getSlot());
        }

        @Specialization(guards = "frame.isDouble(getSlot())")
        protected final double readDouble(final Frame frame) {
            return FrameUtil.getDoubleSafe(frame, getSlot());
        }
    }

    protected abstract static class FrameSlotReadNoClearNode extends AbstractFrameSlotReadNode {

        @Specialization(replaces = {"readBoolean", "readLong", "readDouble"})
        protected final Object readObject(final Frame frame) {
            if (!frame.isObject(getSlot())) {
                /*
                 * The FrameSlotKind has been set to Object, so from now on all writes to the slot
                 * will be Object writes. However, now we are in a frame that still has an old
                 * non-Object value. This is a slow-path operation: we read the non-Object value,
                 * and write it immediately as an Object value so that we do not hit this path again
                 * multiple times for the same slot of the same frame.
                 */
                CompilerDirectives.transferToInterpreter();
                final Object value = frame.getValue(getSlot());
                assert value != null : "Unexpected `null` value";
                frame.setObject(getSlot(), value);
                return value;
            } else {
                return FrameUtil.getObjectSafe(frame, getSlot());
            }
        }
    }

    protected abstract static class FrameSlotReadClearNode extends AbstractFrameSlotReadNode {

        @Specialization(replaces = {"readBoolean", "readLong", "readDouble"})
        protected final Object readAndClearObject(final Frame frame) {
            final Object value;
            if (!frame.isObject(getSlot())) {
                /*
                 * The FrameSlotKind has been set to Object, so from now on all writes to the slot
                 * will be Object writes. However, now we are in a frame that still has an old
                 * non-Object value. This is a slow-path operation: we read the non-Object value,
                 * and clear it immediately as an Object value so that we do not hit this path again
                 * multiple times for the same slot of the same frame.
                 */
                CompilerDirectives.transferToInterpreter();
                value = frame.getValue(getSlot());
            } else {
                value = FrameUtil.getObjectSafe(frame, getSlot());
            }
            frame.setObject(getSlot(), null);
            return value;
        }
    }

    private static final class FrameArgumentNode extends FrameStackReadNode {
        private static final EconomicMap<Integer, FrameArgumentNode> SINGLETONS = EconomicMap.create();

        private final int index;

        private FrameArgumentNode(final int index) {
            this.index = FrameAccess.getArgumentStartIndex() + index;
        }

        private static FrameArgumentNode getOrCreate(final int index) {
            CompilerAsserts.neverPartOfCompilation();
            FrameArgumentNode node = SINGLETONS.get(index);
            if (node == null) {
                node = new FrameArgumentNode(index);
                SINGLETONS.put(index, node);
            }
            return node;
        }

        @Override
        public Object executeReadUnsafe(final Frame frame) {
            return frame.getArguments()[index];
        }

        @Override
        public boolean isAdoptable() {
            return false;
        }

        @Override
        public Node copy() {
            return Objects.requireNonNull(SINGLETONS.get(index));
        }

        @Override
        public Node deepCopy() {
            return copy();
        }
    }
}
