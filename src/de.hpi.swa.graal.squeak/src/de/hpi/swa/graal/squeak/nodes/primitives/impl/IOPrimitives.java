package de.hpi.swa.graal.squeak.nodes.primitives.impl;

import java.util.List;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;

import de.hpi.swa.graal.squeak.exceptions.PrimitiveExceptions.PrimitiveFailed;
import de.hpi.swa.graal.squeak.exceptions.SqueakExceptions.SqueakException;
import de.hpi.swa.graal.squeak.image.SqueakImageContext;
import de.hpi.swa.graal.squeak.io.DisplayPoint;
import de.hpi.swa.graal.squeak.io.SqueakIOConstants;
import de.hpi.swa.graal.squeak.model.AbstractSqueakObject;
import de.hpi.swa.graal.squeak.model.ArrayObject;
import de.hpi.swa.graal.squeak.model.CompiledBlockObject;
import de.hpi.swa.graal.squeak.model.CompiledMethodObject;
import de.hpi.swa.graal.squeak.model.FloatObject;
import de.hpi.swa.graal.squeak.model.LargeIntegerObject;
import de.hpi.swa.graal.squeak.model.NativeObject;
import de.hpi.swa.graal.squeak.model.NotProvided;
import de.hpi.swa.graal.squeak.model.ObjectLayouts.ERROR_TABLE;
import de.hpi.swa.graal.squeak.model.ObjectLayouts.FORM;
import de.hpi.swa.graal.squeak.model.ObjectLayouts.SPECIAL_OBJECT_INDEX;
import de.hpi.swa.graal.squeak.model.PointersObject;
import de.hpi.swa.graal.squeak.model.WeakPointersObject;
import de.hpi.swa.graal.squeak.nodes.AbstractNodeWithImage;
import de.hpi.swa.graal.squeak.nodes.accessing.ArrayObjectNodes.GetObjectArrayNode;
import de.hpi.swa.graal.squeak.nodes.accessing.SqueakObjectAt0Node;
import de.hpi.swa.graal.squeak.nodes.accessing.SqueakObjectAtPut0Node;
import de.hpi.swa.graal.squeak.nodes.accessing.SqueakObjectInstSizeNode;
import de.hpi.swa.graal.squeak.nodes.accessing.SqueakObjectSizeNode;
import de.hpi.swa.graal.squeak.nodes.primitives.AbstractPrimitiveFactoryHolder;
import de.hpi.swa.graal.squeak.nodes.primitives.AbstractPrimitiveNode;
import de.hpi.swa.graal.squeak.nodes.primitives.SqueakPrimitive;
import de.hpi.swa.graal.squeak.nodes.primitives.impl.IOPrimitivesFactory.PrimScanCharactersNodeFactory.ScanCharactersHelperNodeGen;

public final class IOPrimitives extends AbstractPrimitiveFactoryHolder {

    @Override
    public List<NodeFactory<? extends AbstractPrimitiveNode>> getFactories() {
        return IOPrimitivesFactory.getFactories();
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 90)
    protected abstract static class PrimMousePointNode extends AbstractPrimitiveNode {
        private static final DisplayPoint NULL_POINT = new DisplayPoint(0, 0);

        protected PrimMousePointNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization(guards = "code.image.hasDisplay()")
        protected final Object doMousePoint() {
            return code.image.wrap(code.image.getDisplay().getLastMousePosition());
        }

        @Specialization(guards = "!code.image.hasDisplay()")
        protected final Object doMousePointHeadless() {
            return code.image.wrap(NULL_POINT);
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 91)
    protected abstract static class PrimTestDisplayDepthNode extends AbstractPrimitiveNode {
        private static final int[] SUPPORTED_DEPTHS = new int[]{32}; // TODO: support all depths?
                                                                     // {1, 2, 4, 8, 16, 32}

        protected PrimTestDisplayDepthNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization
        protected final Object doTest(@SuppressWarnings("unused") final AbstractSqueakObject receiver, final long depth) {
            for (int i = 0; i < SUPPORTED_DEPTHS.length; i++) {
                if (SUPPORTED_DEPTHS[i] == depth) {
                    return code.image.sqTrue;
                }
            }
            return code.image.sqFalse;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 92)
    protected abstract static class PrimSetDisplayModeNode extends AbstractPrimitiveNode {

        protected PrimSetDisplayModeNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization(guards = "code.image.hasDisplay()")
        protected final Object doSet(final AbstractSqueakObject receiver, final long depth, final long width, final long height, final boolean fullscreen) {
            code.image.getDisplay().adjustDisplay(depth, width, height, fullscreen);
            return receiver;
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!code.image.hasDisplay()")
        protected static final Object doSetHeadless(final AbstractSqueakObject receiver, final long depth, final long width, final long height, final boolean fullscreen) {
            return receiver;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 93)
    protected abstract static class PrimInputSemaphoreNode extends AbstractPrimitiveNode {

        protected PrimInputSemaphoreNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization(guards = "code.image.hasDisplay()")
        protected final Object doSet(final AbstractSqueakObject receiver, final long semaIndex) {
            code.image.getDisplay().setInputSemaphoreIndex((int) semaIndex);
            return receiver;
        }

        @Specialization(guards = "!code.image.hasDisplay()")
        protected static final Object doSetHeadless(final AbstractSqueakObject receiver, @SuppressWarnings("unused") final long semaIndex) {
            return receiver;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 94)
    protected abstract static class PrimGetNextEventNode extends AbstractPrimitiveNode {

        protected PrimGetNextEventNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization(guards = "code.image.hasDisplay()")
        protected final PointersObject doGetNext(final PointersObject eventSensor, final ArrayObject targetArray) {
            targetArray.setStorage(code.image.getDisplay().getNextEvent());
            return eventSensor;
        }

        @Specialization(guards = "!code.image.hasDisplay()")
        protected static final PointersObject doGetNextHeadless(final PointersObject eventSensor, @SuppressWarnings("unused") final ArrayObject targetArray) {
            targetArray.setStorage(SqueakIOConstants.NULL_EVENT);
            return eventSensor;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 96)
    protected abstract static class PrimCopyBitsNode extends SimulationPrimitiveNode {

        protected PrimCopyBitsNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments, "BitBltPlugin", "primitiveCopyBits");
        }

    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 101)
    protected abstract static class PrimBeCursorNode extends AbstractPrimitiveNode {

        protected PrimBeCursorNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization(guards = "code.image.hasDisplay()")
        protected final Object doCursor(final PointersObject receiver, @SuppressWarnings("unused") final NotProvided mask) {
            code.image.getDisplay().setCursor(validateAndExtractWords(receiver), null, extractDepth(receiver));
            return receiver;
        }

        @Specialization(guards = "code.image.hasDisplay()")
        protected final Object doCursor(final PointersObject receiver, final PointersObject maskObject) {
            final int[] words = validateAndExtractWords(receiver);
            final int depth = extractDepth(receiver);
            if (depth == 1) {
                final int[] mask = ((NativeObject) maskObject.at0(FORM.BITS)).getIntStorage();
                code.image.getDisplay().setCursor(words, mask, 2);
            } else {
                code.image.getDisplay().setCursor(words, null, depth);
            }
            return receiver;
        }

        @Specialization(guards = "!code.image.hasDisplay()")
        protected static final Object doCursorHeadless(final PointersObject receiver, @SuppressWarnings("unused") final NotProvided mask) {
            return receiver;
        }

        @Specialization(guards = "!code.image.hasDisplay()")
        protected static final Object doCursorHeadless(final PointersObject receiver, @SuppressWarnings("unused") final PointersObject maskObject) {
            return receiver;
        }

        private static int[] validateAndExtractWords(final PointersObject receiver) {
            final int[] words = ((NativeObject) receiver.at0(FORM.BITS)).getIntStorage();
            final long width = (long) receiver.at0(FORM.WIDTH);
            final long height = (long) receiver.at0(FORM.HEIGHT);
            if (width != SqueakIOConstants.CURSOR_WIDTH || height != SqueakIOConstants.CURSOR_HEIGHT) {
                throw new SqueakException("Unexpected cursor width:", width, "or height:", height);
            }
            return words;
        }

        private static int extractDepth(final PointersObject receiver) {
            return (int) (long) receiver.at0(FORM.DEPTH);
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 102)
    protected abstract static class PrimBeDisplayNode extends AbstractPrimitiveNode {

        protected PrimBeDisplayNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization(guards = {"code.image.hasDisplay()", "receiver.size() >= 4"})
        protected final boolean doDisplay(final PointersObject receiver) {
            code.image.specialObjectsArray.atput0Object(SPECIAL_OBJECT_INDEX.TheDisplay, receiver);
            code.image.getDisplay().open(receiver);
            return code.image.sqTrue;
        }

        @Specialization(guards = {"!code.image.hasDisplay()"})
        protected final boolean doDisplayHeadless(@SuppressWarnings("unused") final PointersObject receiver) {
            return code.image.sqFalse;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 103)
    protected abstract static class PrimScanCharactersNode extends AbstractPrimitiveNode {
        @Child protected SqueakObjectSizeNode sizeNode = SqueakObjectSizeNode.create();
        @Child private SqueakObjectAt0Node at0Node = SqueakObjectAt0Node.create();

        protected PrimScanCharactersNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization(guards = {"startIndex > 0", "stopIndex > 0", "sourceString.isByteType()", "receiver.size() >= 4", "sizeNode.execute(stops) >= 258"})
        protected final Object doScan(final PointersObject receiver, final long startIndex, final long stopIndex, final NativeObject sourceString, final long rightX,
                        final ArrayObject stops, final long kernData,
                        @Cached("createScanCharactersHelperNode()") final ScanCharactersHelperNode scanNode) {
            final Object scanDestX = at0Node.execute(receiver, 0);
            final Object scanXTable = at0Node.execute(receiver, 2);
            final Object scanMap = at0Node.execute(receiver, 3);
            return scanNode.executeScan(receiver, startIndex, stopIndex, sourceString.getByteStorage(), rightX, stops, kernData, scanDestX, scanXTable, scanMap);
        }

        protected final ScanCharactersHelperNode createScanCharactersHelperNode() {
            return ScanCharactersHelperNode.create(code.image);
        }

        protected abstract static class ScanCharactersHelperNode extends AbstractNodeWithImage {
            private static final long END_OF_RUN = 257 - 1;
            private static final long CROSSED_X = 258 - 1;

            @Child protected SqueakObjectSizeNode sizeNode = SqueakObjectSizeNode.create();
            @Child private SqueakObjectAt0Node at0Node = SqueakObjectAt0Node.create();
            @Child private SqueakObjectAtPut0Node atPut0Node = SqueakObjectAtPut0Node.create();

            protected static ScanCharactersHelperNode create(final SqueakImageContext image) {
                return ScanCharactersHelperNodeGen.create(image);
            }

            protected abstract Object executeScan(PointersObject receiver, long startIndex, long stopIndex, byte[] sourceBytes, long rightX, ArrayObject stops, long kernData,
                            Object scanDestX, Object scanXTable, Object scanMap);

            protected ScanCharactersHelperNode(final SqueakImageContext image) {
                super(image);
            }

            @Specialization(guards = {"sizeNode.execute(scanMap) == 256", "stopIndex <= sourceBytes.length"})
            protected final Object doScan(final PointersObject receiver, final long startIndex, final long stopIndex, final byte[] sourceBytes, final long rightX, final ArrayObject stops,
                            final long kernData, final long startScanDestX, final ArrayObject scanXTable, final ArrayObject scanMap) {
                final int maxGlyph = sizeNode.execute(scanXTable) - 2;
                long scanDestX = startScanDestX;
                long scanLastIndex = startIndex;
                while (scanLastIndex <= stopIndex) {
                    final long ascii = (sourceBytes[(int) (scanLastIndex - 1)] & 0xFF);
                    final Object stopReason = at0Node.execute(stops, ascii);
                    if (stopReason != image.nil) {
                        storeStateInReceiver(receiver, scanDestX, scanLastIndex);
                        return stopReason;
                    }
                    if (ascii < 0 || sizeNode.execute(scanMap) <= ascii) {
                        throw new PrimitiveFailed();
                    }
                    final long glyphIndex = (long) at0Node.execute(scanMap, ascii);
                    if (glyphIndex < 0 || glyphIndex > maxGlyph) {
                        throw new PrimitiveFailed();
                    }
                    final long sourceX1;
                    final long sourceX2;
                    try {
                        sourceX1 = (long) at0Node.execute(scanXTable, glyphIndex);
                        sourceX2 = (long) at0Node.execute(scanXTable, glyphIndex + 1);
                    } catch (ClassCastException e) {
                        throw new PrimitiveFailed();
                    }
                    final long nextDestX = scanDestX + sourceX2 - sourceX1;
                    if (nextDestX > rightX) {
                        storeStateInReceiver(receiver, scanDestX, scanLastIndex);
                        return at0Node.execute(stops, CROSSED_X);
                    }
                    scanDestX = nextDestX + kernData;
                    scanLastIndex++;
                }
                storeStateInReceiver(receiver, scanDestX, stopIndex);
                return at0Node.execute(stops, END_OF_RUN);
            }

            private void storeStateInReceiver(final PointersObject receiver, final long scanDestX, final long scanLastIndex) {
                atPut0Node.execute(receiver, 0, scanDestX);
                atPut0Node.execute(receiver, 1, scanLastIndex);
            }
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 105)
    protected abstract static class PrimStringReplaceNode extends AbstractPrimitiveNode {
        @Child private SqueakObjectInstSizeNode instSizeNode = SqueakObjectInstSizeNode.create();
        @Child private SqueakObjectSizeNode sizeNode = SqueakObjectSizeNode.create();
        @Child private SqueakObjectAt0Node at0Node;
        @Child private SqueakObjectAtPut0Node atPut0Node;

        protected PrimStringReplaceNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        public abstract Object executeReplace(VirtualFrame frame);

        @Specialization(guards = "!isSmallInteger(repl)")
        protected final Object replace(final LargeIntegerObject rcvr, final long start, final long stop, final long repl, final long replStart) {
            return doLargeInteger(rcvr, start, stop, asLargeInteger(repl), replStart);
        }

        @Specialization(guards = "inBounds(rcvr, start, stop, repl, replStart)")
        protected static final Object doLargeInteger(final LargeIntegerObject rcvr, final long start, final long stop, final LargeIntegerObject repl, final long replStart) {
            final byte[] rcvrBytes = rcvr.getBytes();
            final byte[] replBytes = repl.getBytes();
            final int repOff = (int) (replStart - start);
            for (int i = (int) (start - 1); i < stop; i++) {
                rcvrBytes[i] = replBytes[repOff + i];
            }
            rcvr.setBytes(rcvrBytes);
            return rcvr;
        }

        @Specialization(guards = "inBounds(rcvr, start, stop, repl, replStart)")
        protected static final Object doLargeIntegerFloat(final LargeIntegerObject rcvr, final long start, final long stop, final FloatObject repl, final long replStart) {
            final byte[] rcvrBytes = rcvr.getBytes();
            final byte[] replBytes = repl.getBytes();
            final int repOff = (int) (replStart - start);
            for (int i = (int) (start - 1); i < stop; i++) {
                rcvrBytes[i] = replBytes[repOff + i];
            }
            rcvr.setBytes(rcvrBytes);
            return rcvr;
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "repl.isByteType()"})
        protected static final Object doLargeIntegerNative(final LargeIntegerObject rcvr, final long start, final long stop, final NativeObject repl, final long replStart) {
            final byte[] rcvrBytes = rcvr.getBytes();
            final byte[] replBytes = repl.getByteStorage();
            final int repOff = (int) (replStart - start);
            for (int i = (int) (start - 1); i < stop; i++) {
                rcvrBytes[i] = replBytes[repOff + i];
            }
            rcvr.setBytes(rcvrBytes);
            return rcvr;
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.haveSameStorageType(repl)"})
        protected final Object doNative(final NativeObject rcvr, final long start, final long stop, final NativeObject repl, final long replStart) {
            final int repOff = (int) (replStart - start);
            for (int i = (int) (start - 1); i < stop; i++) {
                getAtPut0Node().execute(rcvr, i, getAt0Node().execute(repl, repOff + i));
            }
            return rcvr;
        }

        @Specialization(guards = "!isSmallInteger(repl)")
        protected final Object doNativeLargeInteger(final NativeObject rcvr, final long start, final long stop, final long repl, final long replStart) {
            return doNativeLargeInteger(rcvr, start, stop, asLargeInteger(repl), replStart);
        }

        @Specialization(guards = "inBounds(rcvr, start, stop, repl, replStart)")
        protected final Object doNativeLargeInteger(final NativeObject rcvr, final long start, final long stop, final LargeIntegerObject repl, final long replStart) {
            final int repOff = (int) (replStart - start);
            for (int i = (int) (start - 1); i < stop; i++) {
                getAtPut0Node().execute(rcvr, i, repl.getNativeAt0(repOff + i));
            }
            return rcvr;
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "inBounds(rcvr, start, stop, repl, replStart)")
        protected static final Object doNativeFloat(final NativeObject rcvr, final long start, final long stop, final FloatObject repl, final long replStart) {
            throw new SqueakException("Not supported"); // TODO: check this is needed
        }

        @SuppressWarnings("unused")
        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isEmptyType()", "repl.isEmptyType()"})
        protected static final Object doEmptyArrays(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart) {
            return rcvr; // Nothing to do.
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isEmptyType()", "repl.isAbstractSqueakObjectType()"})
        protected static final Object doEmptyArrayToSqueakObjects(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart) {
            rcvr.transitionFromEmptyToAbstractSqueakObjects();
            return doArraysOfSqueakObjects(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isEmptyType()", "repl.isBooleanType()"})
        protected static final Object doEmptyArrayToBooleans(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart) {
            rcvr.transitionFromEmptyToBooleans();
            return doArraysOfBooleans(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isEmptyType()", "repl.isCharType()"})
        protected static final Object doEmptyArrayToChars(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart) {
            rcvr.transitionFromEmptyToChars();
            return doArraysOfChars(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isEmptyType()", "repl.isLongType()"})
        protected static final Object doEmptyArrayToLongs(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart) {
            rcvr.transitionFromEmptyToLongs();
            return doArraysOfLongs(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isEmptyType()", "repl.isDoubleType()"})
        protected static final Object doEmptyArrayToDoubles(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart) {
            rcvr.transitionFromDoublesToObjects();
            return doArraysOfDoubles(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isEmptyType()", "repl.isObjectType()"})
        protected static final Object doEmptyArrayToObjects(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart) {
            rcvr.transitionFromEmptyToObjects();
            return doArraysOfObjects(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isAbstractSqueakObjectType()", "repl.isAbstractSqueakObjectType()"})
        protected static final Object doArraysOfSqueakObjects(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart) {
            final int repOff = (int) (replStart - start);
            final AbstractSqueakObject[] dstArray = rcvr.getAbstractSqueakObjectStorage();
            final AbstractSqueakObject[] srcArray = repl.getAbstractSqueakObjectStorage();
            for (int i = (int) (start - 1); i < stop; i++) {
                dstArray[i] = srcArray[repOff + i];
            }
            return rcvr;
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isAbstractSqueakObjectType()", "!repl.isAbstractSqueakObjectType()"})
        protected static final Object doArraysOfSqueakObjectsTransition(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart,
                        @Cached("create()") final GetObjectArrayNode getObjectArrayNode) {
            rcvr.transitionFromAbstractSqueakObjectsToObjects();
            replaceGeneric(rcvr.getObjectStorage(), start, stop, getObjectArrayNode.execute(repl), replStart);
            return rcvr;
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isBooleanType()", "repl.isBooleanType()"})
        protected static final Object doArraysOfBooleans(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart) {
            final int repOff = (int) (replStart - start);
            final byte[] dstLongs = rcvr.getBooleanStorage();
            final byte[] srcLongs = repl.getBooleanStorage();
            for (int i = (int) (start - 1); i < stop; i++) {
                dstLongs[i] = srcLongs[repOff + i];
            }
            return rcvr;
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isCharType()", "repl.isCharType()"})
        protected static final Object doArraysOfChars(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart) {
            final int repOff = (int) (replStart - start);
            final char[] dstLongs = rcvr.getCharStorage();
            final char[] srcLongs = repl.getCharStorage();
            for (int i = (int) (start - 1); i < stop; i++) {
                dstLongs[i] = srcLongs[repOff + i];
            }
            return rcvr;
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isLongType()", "repl.isLongType()"})
        protected static final Object doArraysOfLongs(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart) {
            final int repOff = (int) (replStart - start);
            final long[] dstLongs = rcvr.getLongStorage();
            final long[] srcLongs = repl.getLongStorage();
            for (int i = (int) (start - 1); i < stop; i++) {
                dstLongs[i] = srcLongs[repOff + i];
            }
            return rcvr;
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isLongType()", "!repl.isLongType()"})
        protected static final Object doArraysOfLongsTransition(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart,
                        @Cached("create()") final GetObjectArrayNode getObjectArrayNode) {
            rcvr.transitionFromLongsToObjects();
            replaceGeneric(rcvr.getObjectStorage(), start, stop, getObjectArrayNode.execute(repl), replStart);
            return rcvr;
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isDoubleType()", "repl.isDoubleType()"})
        protected static final Object doArraysOfDoubles(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart) {
            final int repOff = (int) (replStart - start);
            final double[] dstDoubles = rcvr.getDoubleStorage();
            final double[] srcDoubles = repl.getDoubleStorage();
            for (int i = (int) (start - 1); i < stop; i++) {
                dstDoubles[i] = srcDoubles[repOff + i];
            }
            return rcvr;
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isDoubleType()", "!repl.isDoubleType()"})
        protected static final Object doArraysOfDoublesTransition(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart,
                        @Cached("create()") final GetObjectArrayNode getObjectArrayNode) {
            rcvr.transitionFromDoublesToObjects();
            replaceGeneric(rcvr.getObjectStorage(), start, stop, getObjectArrayNode.execute(repl), replStart);
            return rcvr;
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isObjectType()", "repl.isObjectType()"})
        protected static final Object doArraysOfObjects(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart) {
            final int repOff = (int) (replStart - start);
            final Object[] dstArray = rcvr.getObjectStorage();
            final Object[] srcArray = repl.getObjectStorage();
            for (int i = (int) (start - 1); i < stop; i++) {
                dstArray[i] = srcArray[repOff + i];
            }
            return rcvr;
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isObjectType()", "!repl.isObjectType()"})
        protected static final Object doArraysOfObjects(final ArrayObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart,
                        @Cached("create()") final GetObjectArrayNode getObjectArrayNode) {
            final int repOff = (int) (replStart - start);
            final Object[] dstArray = rcvr.getObjectStorage();
            final Object[] srcArray = getObjectArrayNode.execute(repl);
            for (int i = (int) (start - 1); i < stop; i++) {
                dstArray[i] = srcArray[repOff + i];
            }
            return rcvr;
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isEmptyType()"})
        protected static final Object doEmptyArrayPointers(final ArrayObject rcvr, final long start, final long stop, final PointersObject repl, final long replStart) {
            rcvr.transitionFromEmptyToObjects(); // TODO: could be more efficient?
            return doArrayOfObjectsPointers(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isAbstractSqueakObjectType()"})
        protected static final Object doArrayOfSqueakObjectPointers(final ArrayObject rcvr, final long start, final long stop, final PointersObject repl, final long replStart) {
            rcvr.transitionFromAbstractSqueakObjectsToObjects();
            return doArrayOfObjectsPointers(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isBooleanType()"})
        protected static final Object doArrayOfBooleansPointers(final ArrayObject rcvr, final long start, final long stop, final PointersObject repl, final long replStart) {
            rcvr.transitionFromBooleansToObjects();
            return doArrayOfObjectsPointers(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isCharType()"})
        protected static final Object doArrayOfCharsPointers(final ArrayObject rcvr, final long start, final long stop, final PointersObject repl, final long replStart) {
            rcvr.transitionFromCharsToObjects();
            return doArrayOfObjectsPointers(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isLongType()"})
        protected static final Object doArrayOfLongsPointers(final ArrayObject rcvr, final long start, final long stop, final PointersObject repl, final long replStart) {
            rcvr.transitionFromLongsToObjects();
            return doArrayOfObjectsPointers(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isDoubleType()"})
        protected static final Object doArrayOfDoublesPointers(final ArrayObject rcvr, final long start, final long stop, final PointersObject repl, final long replStart) {
            rcvr.transitionFromDoublesToObjects();
            return doArrayOfObjectsPointers(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isObjectType()"})
        protected static final Object doArrayOfObjectsPointers(final ArrayObject rcvr, final long start, final long stop, final PointersObject repl, final long replStart) {
            final int repOff = (int) (replStart - start);
            final Object[] dstArray = rcvr.getObjectStorage();
            for (int i = (int) (start - 1); i < stop; i++) {
                dstArray[i] = repl.at0(repOff + i);
            }
            return rcvr;
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isEmptyType()"})
        protected static final Object doEmptyArrayWeakPointers(final ArrayObject rcvr, final long start, final long stop, final WeakPointersObject repl, final long replStart) {
            rcvr.transitionFromEmptyToObjects(); // TODO: could be more efficient?
            return doArrayOfObjectsWeakPointers(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isAbstractSqueakObjectType()"})
        protected static final Object doArrayOfSqueakObjectWeakPointers(final ArrayObject rcvr, final long start, final long stop, final WeakPointersObject repl, final long replStart) {
            rcvr.transitionFromAbstractSqueakObjectsToObjects();
            return doArrayOfObjectsWeakPointers(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isBooleanType()"})
        protected static final Object doArrayOfBooleansWeakPointers(final ArrayObject rcvr, final long start, final long stop, final WeakPointersObject repl, final long replStart) {
            rcvr.transitionFromBooleansToObjects();
            return doArrayOfObjectsWeakPointers(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isCharType()"})
        protected static final Object doArrayOfCharsWeakPointers(final ArrayObject rcvr, final long start, final long stop, final WeakPointersObject repl, final long replStart) {
            rcvr.transitionFromCharsToObjects();
            return doArrayOfObjectsWeakPointers(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isLongType()"})
        protected static final Object doArrayOfLongsWeakPointers(final ArrayObject rcvr, final long start, final long stop, final WeakPointersObject repl, final long replStart) {
            rcvr.transitionFromLongsToObjects();
            return doArrayOfObjectsWeakPointers(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isDoubleType()"})
        protected static final Object doArrayOfDoublesWeakPointers(final ArrayObject rcvr, final long start, final long stop, final WeakPointersObject repl, final long replStart) {
            rcvr.transitionFromDoublesToObjects();
            return doArrayOfObjectsWeakPointers(rcvr, start, stop, repl, replStart);
        }

        @Specialization(guards = {"inBounds(rcvr, start, stop, repl, replStart)", "rcvr.isObjectType()"})
        protected static final Object doArrayOfObjectsWeakPointers(final ArrayObject rcvr, final long start, final long stop, final WeakPointersObject repl, final long replStart) {
            final int repOff = (int) (replStart - start);
            final Object[] dstArray = rcvr.getObjectStorage();
            for (int i = (int) (start - 1); i < stop; i++) {
                dstArray[i] = repl.at0(repOff + i);
            }
            return rcvr;
        }

        @Specialization(guards = "inBounds(rcvr, start, stop, repl, replStart)")
        protected static final Object doPointers(final PointersObject rcvr, final long start, final long stop, final PointersObject repl, final long replStart) {
            final long repOff = replStart - start;
            for (int i = (int) (start - 1); i < stop; i++) {
                rcvr.atput0(i, repl.at0(repOff + i));
            }
            return rcvr;
        }

        @Specialization(guards = "inBounds(rcvr, start, stop, repl, replStart)")
        protected static final Object doPointersWeakPointers(final PointersObject rcvr, final long start, final long stop, final WeakPointersObject repl, final long replStart) {
            final long repOff = replStart - start;
            for (int i = (int) (start - 1); i < stop; i++) {
                rcvr.atput0(i, repl.at0(repOff + i));
            }
            return rcvr;
        }

        @Specialization(guards = "inBounds(rcvr, start, stop, repl, replStart)")
        protected static final Object doWeakPointers(final WeakPointersObject rcvr, final long start, final long stop, final WeakPointersObject repl, final long replStart) {
            final long repOff = replStart - start;
            for (int i = (int) (start - 1); i < stop; i++) {
                rcvr.atput0(i, repl.at0(repOff + i));
            }
            return rcvr;
        }

        @Specialization(guards = "inBounds(rcvr, start, stop, repl, replStart)")
        protected final Object doWeakPointersArray(final WeakPointersObject rcvr, final long start, final long stop, final ArrayObject repl, final long replStart) {
            final long repOff = replStart - start;
            for (int i = (int) (start - 1); i < stop; i++) {
                rcvr.atput0(i, getAt0Node().execute(repl, repOff + i));
            }
            return rcvr;
        }

        @Specialization(guards = "inBounds(rcvr, start, stop, repl, replStart)")
        protected static final Object doWeakPointersPointers(final WeakPointersObject rcvr, final long start, final long stop, final PointersObject repl, final long replStart) {
            final long repOff = replStart - start;
            for (int i = (int) (start - 1); i < stop; i++) {
                rcvr.atput0(i, repl.at0(repOff + i));
            }
            return rcvr;
        }

        @Specialization(guards = "inBounds(rcvr, start, stop, repl, replStart)")
        protected static final Object doBlock(final CompiledBlockObject rcvr, final long start, final long stop, final CompiledBlockObject repl, final long replStart) {
            final long repOff = replStart - start;
            for (int i = (int) (start - 1); i < stop; i++) {
                rcvr.atput0(i, repl.at0(repOff + i));
            }
            return rcvr;
        }

        @Specialization(guards = "inBounds(rcvr, start, stop, repl, replStart)")
        protected static final Object doMethod(final CompiledMethodObject rcvr, final long start, final long stop, final CompiledMethodObject repl, final long replStart) {
            final long repOff = replStart - start;
            for (int i = (int) (start - 1); i < stop; i++) {
                rcvr.atput0(i, repl.at0(repOff + i));
            }
            return rcvr;
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!inBounds(rcvr, start, stop, repl, replStart)")
        protected static final Object doBadIndex(final AbstractSqueakObject rcvr, final long start, final long stop, final AbstractSqueakObject repl, final long replStart) {
            throw new PrimitiveFailed(ERROR_TABLE.BAD_INDEX);
        }

        protected final boolean inBounds(final AbstractSqueakObject array, final long start, final long stop, final AbstractSqueakObject repl, final long replStart) {
            return (start >= 1 && (start - 1) <= stop && (stop + instSizeNode.execute(array)) <= sizeNode.execute(array)) &&
                            (replStart >= 1 && (stop - start + replStart + instSizeNode.execute(repl) <= sizeNode.execute(repl)));
        }

        private static void replaceGeneric(final Object[] dstArray, final long start, final long stop, final Object[] srcArray, final long replStart) {
            final int repOff = (int) (replStart - start);
            for (int i = (int) (start - 1); i < stop; i++) {
                dstArray[i] = srcArray[repOff + i];
            }
        }

        private SqueakObjectAt0Node getAt0Node() {
            if (at0Node == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                at0Node = insert(SqueakObjectAt0Node.create());
            }
            return at0Node;
        }

        private SqueakObjectAtPut0Node getAtPut0Node() {
            if (atPut0Node == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                atPut0Node = insert(SqueakObjectAtPut0Node.create());
            }
            return atPut0Node;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 106)
    protected abstract static class PrimScreenSizeNode extends AbstractPrimitiveNode {

        protected PrimScreenSizeNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization
        protected final AbstractSqueakObject doSize(@SuppressWarnings("unused") final AbstractSqueakObject receiver) {
            return code.image.wrap(code.image.flags.getLastWindowSize());
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 107)
    protected abstract static class PrimMouseButtonsNode extends AbstractPrimitiveNode {

        protected PrimMouseButtonsNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization(guards = "code.image.hasDisplay()")
        protected final Object doMouseButtons(@SuppressWarnings("unused") final AbstractSqueakObject receiver) {
            return code.image.wrap(code.image.getDisplay().getLastMouseButton());
        }

        @Specialization(guards = "!code.image.hasDisplay()")
        protected final Object doMouseButtonsHeadless(@SuppressWarnings("unused") final AbstractSqueakObject receiver) {
            return code.image.wrap(0L);
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 108)
    protected abstract static class PrimKeyboardNextNode extends AbstractPrimitiveNode {

        protected PrimKeyboardNextNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization(guards = "code.image.hasDisplay()")
        protected final Object doNext(@SuppressWarnings("unused") final AbstractSqueakObject receiver) {
            final int keyboardNext = code.image.getDisplay().keyboardNext();
            if (keyboardNext == 0) {
                return code.image.nil;
            } else {
                return code.image.wrap(keyboardNext);
            }
        }

        @Specialization(guards = "!code.image.hasDisplay()")
        protected final Object doNextHeadless(@SuppressWarnings("unused") final AbstractSqueakObject receiver) {
            return code.image.nil;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 109)
    protected abstract static class PrimKeyboardPeekNode extends AbstractPrimitiveNode {

        protected PrimKeyboardPeekNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization(guards = "code.image.hasDisplay()")
        protected final Object doPeek(@SuppressWarnings("unused") final AbstractSqueakObject receiver) {
            final int keyboardPeek = code.image.getDisplay().keyboardPeek();
            if (keyboardPeek == 0) {
                return code.image.nil;
            } else {
                return code.image.wrap(keyboardPeek);
            }
        }

        @Specialization(guards = "!code.image.hasDisplay()")
        protected final Object doPeekHeadless(@SuppressWarnings("unused") final AbstractSqueakObject receiver) {
            return code.image.nil;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 126)
    protected abstract static class PrimDeferDisplayUpdatesNode extends AbstractPrimitiveNode {

        public PrimDeferDisplayUpdatesNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization
        protected static final AbstractSqueakObject doDefer(final AbstractSqueakObject receiver, @SuppressWarnings("unused") final boolean flag) {
            // TODO: uncomment: code.image.display.setDeferUpdates(flag);
            return receiver;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 127)
    protected abstract static class PrimDrawRectNode extends AbstractPrimitiveNode {

        protected PrimDrawRectNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization(guards = {"code.image.hasDisplay()", "inBounds(left, right, top, bottom)"})
        protected final AbstractSqueakObject doDraw(final PointersObject receiver, final long left, final long right, final long top, final long bottom) {
            code.image.getDisplay().forceRect((int) left, (int) right, (int) top, (int) bottom);
            return receiver;
        }

        @SuppressWarnings("unused")
        @Specialization(guards = {"!code.image.hasDisplay()", "inBounds(left, right, top, bottom)"})
        protected static final AbstractSqueakObject doDrawHeadless(final PointersObject receiver, final long left, final long right, final long top, final long bottom) {
            return receiver;
        }

        protected static final boolean inBounds(final long left, final long right, final long top, final long bottom) {
            return (left <= right) && (top <= bottom);
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 133)
    protected abstract static class PrimSetInterruptKeyNode extends AbstractPrimitiveNode {

        protected PrimSetInterruptKeyNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization
        protected static final AbstractSqueakObject set(final AbstractSqueakObject receiver) {
            // TODO: interrupt key is obsolete in image, but maybe still needed in the vm?
            return receiver;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(index = 140)
    protected abstract static class PrimBeepNode extends AbstractPrimitiveNode {

        protected PrimBeepNode(final CompiledMethodObject method, final int numArguments) {
            super(method, numArguments);
        }

        @Specialization(guards = "code.image.hasDisplay()")
        protected final AbstractSqueakObject doBeep(final AbstractSqueakObject receiver) {
            code.image.getDisplay().beep();
            return receiver;
        }

        @Specialization(guards = "!code.image.hasDisplay()")
        protected final AbstractSqueakObject doNothing(final AbstractSqueakObject receiver) {
            code.image.printToStdOut((char) 7);
            return receiver;
        }
    }
}
