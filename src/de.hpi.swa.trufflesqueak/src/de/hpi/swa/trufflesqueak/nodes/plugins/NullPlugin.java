/*
 * Copyright (c) 2017-2023 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021-2023 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.plugins;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DenyReplace;
import com.oracle.truffle.api.nodes.Node;

import de.hpi.swa.trufflesqueak.image.SqueakImageConstants;
import de.hpi.swa.trufflesqueak.model.ArrayObject;
import de.hpi.swa.trufflesqueak.model.BooleanObject;
import de.hpi.swa.trufflesqueak.model.PointersObject;
import de.hpi.swa.trufflesqueak.nodes.accessing.AbstractPointersObjectNodes.AbstractPointersObjectWriteNode;
import de.hpi.swa.trufflesqueak.nodes.accessing.ArrayObjectNodes.ArrayObjectSizeNode;
import de.hpi.swa.trufflesqueak.nodes.accessing.ArrayObjectNodes.ArrayObjectWriteNode;
import de.hpi.swa.trufflesqueak.nodes.primitives.AbstractPrimitiveFactoryHolder;
import de.hpi.swa.trufflesqueak.nodes.primitives.AbstractPrimitiveNode;
import de.hpi.swa.trufflesqueak.nodes.primitives.AbstractSingletonPrimitiveNode;
import de.hpi.swa.trufflesqueak.nodes.primitives.PrimitiveFallbacks.BinaryPrimitiveFallback;
import de.hpi.swa.trufflesqueak.nodes.primitives.SqueakPrimitive;
import de.hpi.swa.trufflesqueak.util.MiscUtils;

public final class NullPlugin extends AbstractPrimitiveFactoryHolder {
    @DenyReplace
    @SqueakPrimitive(names = "primitiveScreenScaleFactor")
    public static final class PrimScreenScaleFactorNode extends AbstractSingletonPrimitiveNode {
        @Override
        public Object execute() {
            return 1.0d;
        }
    }

    @DenyReplace
    @SqueakPrimitive(names = "primitiveHighResClock")
    public static final class PrimHighResClockNode extends AbstractSingletonPrimitiveNode {
        @Override
        public Object execute() {
            return System.nanoTime();
        }
    }

    @DenyReplace
    @SqueakPrimitive(names = "primitiveMultipleBytecodeSetsActive")
    public static final class PrimMultipleBytecodeSetsActive0Node extends AbstractSingletonPrimitiveNode {
        @Override
        public Object execute() {
            return BooleanObject.wrap((getContext().imageFormat & SqueakImageConstants.MULTIPLE_BYTECODE_SETS_BITMASK) != 0);
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveMultipleBytecodeSetsActive")
    protected abstract static class PrimMultipleBytecodeSetsActive1Node extends AbstractPrimitiveNode implements BinaryPrimitiveFallback {
        @Specialization
        protected final boolean doSet(@SuppressWarnings("unused") final Object receiver, final boolean value) {
            final int imageFormat = getContext().imageFormat;
            getContext().imageFormat = value ? imageFormat | SqueakImageConstants.MULTIPLE_BYTECODE_SETS_BITMASK : imageFormat & ~SqueakImageConstants.MULTIPLE_BYTECODE_SETS_BITMASK;
            return value;
        }
    }

    @DenyReplace
    @SqueakPrimitive(names = "primitiveUtcWithOffset")
    public static final class PrimUtcWithOffset1Node extends AbstractSingletonPrimitiveNode {
        @Override
        public Object execute() {
            return getContext().asArrayOfLongs(getUTCMicroseconds(), getOffsetFromGTMInSeconds());
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(names = "primitiveUtcWithOffset")
    protected abstract static class PrimUtcWithOffset2Node extends AbstractPrimitiveNode implements BinaryPrimitiveFallback {
        @Specialization(guards = "objectWithTwoSlots.size() == 2")
        protected static final PointersObject doUTC(@SuppressWarnings("unused") final Object receiver, final PointersObject objectWithTwoSlots,
                        @Cached final AbstractPointersObjectWriteNode writeNode) {
            writeNode.execute(objectWithTwoSlots, 0, getUTCMicroseconds());
            writeNode.execute(objectWithTwoSlots, 1, getOffsetFromGTMInSeconds());
            return objectWithTwoSlots;
        }

        @Specialization(guards = "sizeNode.execute(node, arrayWithTwoSlots) == 2", limit = "1")
        protected static final ArrayObject doUTC(@SuppressWarnings("unused") final Object receiver, final ArrayObject arrayWithTwoSlots,
                        @Bind("this") final Node node,
                        @SuppressWarnings("unused") @Cached final ArrayObjectSizeNode sizeNode,
                        @Cached(inline = true) final ArrayObjectWriteNode writeNode) {
            writeNode.execute(node, arrayWithTwoSlots, 0, getUTCMicroseconds());
            writeNode.execute(node, arrayWithTwoSlots, 1, getOffsetFromGTMInSeconds());
            return arrayWithTwoSlots;
        }
    }

    private static long getUTCMicroseconds() {
        return MiscUtils.currentTimeMillis() * 1000L;
    }

    @TruffleBoundary
    private static long getOffsetFromGTMInSeconds() {
        return ZonedDateTime.now().getOffset().getTotalSeconds();
    }

    @Override
    public List<? extends NodeFactory<? extends AbstractPrimitiveNode>> getFactories() {
        return NullPluginFactory.getFactories();
    }

    @Override
    public List<Class<? extends AbstractSingletonPrimitiveNode>> getSingletonPrimitives() {
        return Arrays.asList(PrimScreenScaleFactorNode.class, PrimHighResClockNode.class, PrimMultipleBytecodeSetsActive0Node.class, PrimUtcWithOffset1Node.class);
    }
}
