/*
 * Copyright (c) 2017-2020 Software Architecture Group, Hasso Plattner Institute
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;

import de.hpi.swa.trufflesqueak.exceptions.PrimitiveExceptions.PrimitiveFailed;
import de.hpi.swa.trufflesqueak.exceptions.SqueakExceptions.SqueakException;
import de.hpi.swa.trufflesqueak.image.SqueakImageChunk;
import de.hpi.swa.trufflesqueak.image.SqueakImageConstants;
import de.hpi.swa.trufflesqueak.image.SqueakImageContext;
import de.hpi.swa.trufflesqueak.image.SqueakImageWriter;
import de.hpi.swa.trufflesqueak.interop.WrapToSqueakNode;
import de.hpi.swa.trufflesqueak.nodes.DispatchUneagerlyNode;
import de.hpi.swa.trufflesqueak.nodes.LookupMethodNode;
import de.hpi.swa.trufflesqueak.nodes.accessing.NativeObjectNodes.NativeObjectSizeNode;
import de.hpi.swa.trufflesqueak.nodes.accessing.NativeObjectNodes.NativeObjectWriteNode;
import de.hpi.swa.trufflesqueak.nodes.accessing.SqueakObjectClassNode;
import de.hpi.swa.trufflesqueak.nodes.context.frame.GetOrCreateContextNode;
import de.hpi.swa.trufflesqueak.util.UnsafeUtils;

@ExportLibrary(InteropLibrary.class)
public final class NativeObject extends AbstractSqueakObjectWithClassAndHash {
    public static final short BYTE_MAX = (short) (Math.pow(2, Byte.SIZE) - 1);
    public static final int SHORT_MAX = (int) (Math.pow(2, Short.SIZE) - 1);
    public static final long INTEGER_MAX = (long) (Math.pow(2, Integer.SIZE) - 1);
    public static final int BYTE_TO_WORD = Long.SIZE / Byte.SIZE;
    public static final int SHORT_TO_WORD = Long.SIZE / Short.SIZE;
    public static final int INTEGER_TO_WORD = Long.SIZE / Integer.SIZE;

    @CompilationFinal(dimensions = 1) private byte[] storage;

    public NativeObject(final SqueakImageContext image) { // constructor for special selectors
        super(image, AbstractSqueakObjectWithHash.HASH_UNINITIALIZED, null);
        storage = null;
    }

    private NativeObject(final SqueakImageContext image, final ClassObject classObject, final byte[] storage) {
        super(image, classObject);
        assert storage != null : "Unexpected `null` value";
        this.storage = storage;
    }

    private NativeObject(final SqueakImageContext image, final long hash, final ClassObject classObject, final byte[] storage) {
        super(image, hash, classObject);
        assert storage != null : "Unexpected `null` value";
        this.storage = storage;
    }

    private NativeObject(final NativeObject original) {
        super(original);
        storage = original.storage.clone();
    }

    public static NativeObject newNativeBytes(final SqueakImageChunk chunk) {
        return new NativeObject(chunk.getImage(), chunk.getHash(), chunk.getSqClass(), chunk.getBytes());
    }

    public static NativeObject newNativeBytes(final SqueakImageContext img, final ClassObject klass, final byte[] bytes) {
        return new NativeObject(img, klass, bytes);
    }

    public static NativeObject newNativeBytes(final SqueakImageContext img, final ClassObject klass, final int size) {
        return newNativeBytes(img, klass, new byte[size]);
    }

    public static NativeObject newNativeInts(final SqueakImageChunk chunk) {
        return new NativeObject(chunk.getImage(), chunk.getHash(), chunk.getSqClass(), chunk.getBytes());
    }

    public static NativeObject newNativeInts(final SqueakImageContext img, final ClassObject klass, final int size) {
        return newNativeBytes(img, klass, size * Integer.BYTES);
    }

    public static NativeObject newNativeLongs(final SqueakImageChunk chunk) {
        return new NativeObject(chunk.getImage(), chunk.getHash(), chunk.getSqClass(), chunk.getBytes());
    }

    public static NativeObject newNativeLongs(final SqueakImageContext img, final ClassObject klass, final int size) {
        return newNativeBytes(img, klass, size * Long.BYTES);
    }

    public static NativeObject newNativeShorts(final SqueakImageChunk chunk) {
        return new NativeObject(chunk.getImage(), chunk.getHash(), chunk.getSqClass(), chunk.getBytes());
    }

    public static NativeObject newNativeShorts(final SqueakImageContext img, final ClassObject klass, final int size) {
        return newNativeBytes(img, klass, size * Short.BYTES);
    }

    @Override
    public void fillin(final SqueakImageChunk chunk) {
        if (storage == null) { /* Fill in special selectors. */
            setStorage(chunk.getBytes());
        } else if (chunk.getImage().isHeadless() && isByteType()) {
            final SqueakImageContext image = chunk.getImage();
            if (image.getDebugErrorSelector() == null && Arrays.equals(SqueakImageContext.DEBUG_ERROR_SELECTOR_NAME, getByteStorage())) {
                image.setDebugErrorSelector(this);
            } else if (image.getDebugSyntaxErrorSelector() == null && Arrays.equals(SqueakImageContext.DEBUG_SYNTAX_ERROR_SELECTOR_NAME, getByteStorage())) {
                image.setDebugSyntaxErrorSelector(this);
            }
        }
    }

    @Override
    public int getNumSlots() {
        CompilerAsserts.neverPartOfCompilation();
        if (isByteType()) {
            return (int) Math.ceil((double) getByteLength() / BYTE_TO_WORD);
        } else if (isShortType()) {
            return (int) Math.ceil((double) getShortLength() / SHORT_TO_WORD);
        } else if (isIntType()) {
            return (int) Math.ceil((double) getIntLength() / INTEGER_TO_WORD);
        } else if (isLongType()) {
            return getLongLength();
        } else {
            throw SqueakException.create("Unexpected NativeObject");
        }
    }

    @Override
    public int instsize() {
        return 0;
    }

    @Override
    public int size() {
        CompilerAsserts.neverPartOfCompilation();
        return NativeObjectSizeNode.getUncached().execute(this);
    }

    public void become(final NativeObject other) {
        super.becomeOtherClass(other);
        final byte[] otherStorage = other.storage;
        other.setStorage(storage);
        setStorage(otherStorage);
    }

    public NativeObject shallowCopy() {
        return new NativeObject(this);
    }

    public void convertToBytesStorage(final byte[] bytes) {
        assert storage.getClass() != bytes.getClass() : "Converting storage of same type unnecessary";
        setStorage(bytes);
    }

    public byte getByte(final long index) {
        assert isByteType();
        return UnsafeUtils.getByte(storage, index);
    }

    public int getByteUnsigned(final long index) {
        return Byte.toUnsignedInt(getByte(index));
    }

    public void setByte(final long index, final byte value) {
        assert isByteType();
        UnsafeUtils.putByte(storage, index, value);
    }

    public void setByte(final long index, final int value) {
        assert value < 256;
        setByte(index, (byte) value);
    }

    public int getByteLength() {
        return getByteStorage().length;
    }

    public int getByteSize() {
        return storage.length;
    }

    public byte[] getByteStorage() {
        assert isByteType();
        return storage;
    }

    public int getInt(final long index) {
        assert isIntType();
        return UnsafeUtils.getInt(storage, index);
    }

    public void setInt(final long index, final int value) {
        assert isIntType();
        UnsafeUtils.putInt(storage, index, value);
    }

    public int getIntLength() {
        return getByteLength() / Integer.BYTES;
    }

    public int[] getIntStorage() {
        assert isIntType();
        return UnsafeUtils.toInts(storage);
    }

    public long getLong(final long index) {
        assert isLongType();
        return UnsafeUtils.getLong(storage, index);
    }

    public void setLong(final long index, final long value) {
        assert isLongType();
        UnsafeUtils.putLong(storage, index, value);
    }

    public int getLongLength() {
        return getByteLength() / Long.BYTES;
    }

    private long[] getLongStorage() {
        assert isLongType();
        return UnsafeUtils.toLongs(storage);
    }

    public short getShort(final long index) {
        assert isShortType();
        return UnsafeUtils.getShort(storage, index);
    }

    public void setShort(final long index, final short value) {
        assert isShortType();
        UnsafeUtils.putShort(storage, index, value);
    }

    public int getShortLength() {
        return getByteLength() / Short.BYTES;
    }

    private short[] getShortStorage() {
        assert isShortType();
        return UnsafeUtils.toShorts(storage);
    }

    public boolean isByteType() {
        return getSqueakClass().isBytes();
    }

    public boolean isIntType() {
        return getSqueakClass().isWords();
    }

    public boolean isLongType() {
        return getSqueakClass().isLongs();
    }

    public boolean isShortType() {
        return getSqueakClass().isShorts();
    }

    public void setStorage(final byte[] storage) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        this.storage = storage;
    }

    @TruffleBoundary
    public String asStringUnsafe() {
        return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(storage)).toString();
    }

    @TruffleBoundary
    public String asStringFromWideString() {
        final int[] ints = getIntStorage();
        return new String(ints, 0, ints.length);
    }

    @Override
    public String toString() {
        CompilerAsserts.neverPartOfCompilation();
        if (isByteType()) {
            final ClassObject squeakClass = getSqueakClass();
            if (squeakClass.isStringClass()) {
                final String fullString = asStringUnsafe();
                final int fullLength = fullString.length();
                /* Split at first non-printable character. */
                final String displayString = fullString.split("[^\\p{Print}]", 2)[0];
                if (fullLength <= 40 && fullLength == displayString.length()) {
                    /* fullString is short and has printable characters only. */
                    return "'" + fullString + "'";
                }
                return String.format("'%.30s...' (string length: %s)", displayString, fullLength);
            } else if (squeakClass.isSymbolClass()) {
                return "#" + asStringUnsafe();
            } else {
                return "byte[" + getByteLength() + "]";
            }
        } else if (isShortType()) {
            return "short[" + getShortLength() + "]";
        } else if (isIntType()) {
            if (getSqueakClass().isWideStringClass()) {
                return asStringFromWideString();
            } else {
                return "int[" + getIntLength() + "]";
            }
        } else if (isLongType()) {
            return "long[" + getLongLength() + "]";
        } else {
            throw SqueakException.create("Unexpected native object type");
        }
    }

    public boolean isAllowedInHeadlessMode() {
        return !isDebugErrorSelector() && !isDebugSyntaxErrorSelector();
    }

    public boolean isDebugErrorSelector() {
        return this == image.getDebugErrorSelector();
    }

    public boolean isDebugSyntaxErrorSelector() {
        return this == image.getDebugSyntaxErrorSelector();
    }

    public boolean isDoesNotUnderstand() {
        return this == image.doesNotUnderstand;
    }

    public Object executeAsSymbolSlow(final VirtualFrame frame, final Object... receiverAndArguments) {
        CompilerAsserts.neverPartOfCompilation();
        assert getSqueakClass().isSymbolClass();
        final Object method = LookupMethodNode.getUncached().executeLookup(SqueakObjectClassNode.getUncached().executeLookup(receiverAndArguments[0]), this);
        if (method instanceof CompiledCodeObject) {
            return DispatchUneagerlyNode.getUncached().executeDispatch((CompiledCodeObject) method, receiverAndArguments, GetOrCreateContextNode.getOrCreateFromActiveProcessUncached(frame));
        } else {
            throw SqueakException.create("Illegal uncached message send");
        }
    }

    public static boolean needsWideString(final String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 255) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void write(final SqueakImageWriter writer) {
        if (isByteType()) {
            final int numSlots = getNumSlots();
            final int formatOffset = numSlots * BYTE_TO_WORD - getByteLength();
            assert 0 <= formatOffset && formatOffset <= 7 : "too many odd bits (see instSpec)";
            if (writeHeader(writer, formatOffset)) {
                writer.writeBytes(getByteStorage());
                writePaddingIfAny(writer, getByteLength());
            }
        } else if (isShortType()) {
            final int numSlots = getNumSlots();
            final int formatOffset = numSlots * SHORT_TO_WORD - getShortLength();
            assert 0 <= formatOffset && formatOffset <= 3 : "too many odd bits (see instSpec)";
            if (writeHeader(writer, formatOffset)) {
                for (final short value : getShortStorage()) {
                    writer.writeShort(value);
                }
                writePaddingIfAny(writer, getShortLength() * Short.BYTES);
            }
        } else if (isIntType()) {
            final int numSlots = getNumSlots();
            final int formatOffset = numSlots * INTEGER_TO_WORD - getIntLength();
            assert 0 <= formatOffset && formatOffset <= 1 : "too many odd bits (see instSpec)";
            if (writeHeader(writer, formatOffset)) {
                for (final int value : getIntStorage()) {
                    writer.writeInt(value);
                }
                writePaddingIfAny(writer, getIntLength() * Integer.BYTES);
            }
        } else if (isLongType()) {
            if (!writeHeader(writer)) {
                return;
            }
            for (final long value : getLongStorage()) {
                writer.writeLong(value);
            }
            /* Padding not required. */
        } else {
            throw SqueakException.create("Unexpected object");
        }
    }

    private static void writePaddingIfAny(final SqueakImageWriter writer, final int numberOfBytes) {
        final int offset = numberOfBytes % SqueakImageConstants.WORD_SIZE;
        if (offset > 0) {
            writer.writePadding(SqueakImageConstants.WORD_SIZE - offset);
        }
    }

    public void writeAsFreeList(final SqueakImageWriter writer) {
        if (isLongType()) {
            /* Write header. */
            final int numSlots = getLongLength();
            assert numSlots < SqueakImageConstants.OVERFLOW_SLOTS;
            /* Free list is of format 9 and pinned. */
            writer.writeLong(SqueakImageConstants.ObjectHeader.getHeader(numSlots, getSqueakHash(), 9, SqueakImageConstants.WORD_SIZE_CLASS_INDEX_PUN, true));
            /* Write content. */
            for (final long value : getLongStorage()) {
                writer.writeLong(value);
            }
        } else {
            throw SqueakException.create("Trying to write unexpected hidden native object");
        }
    }

    /*
     * INTEROPERABILITY
     */

    @SuppressWarnings("static-method")
    @ExportMessage
    protected boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    protected long getArraySize(@Shared("sizeNode") @Cached final NativeObjectSizeNode sizeNode) {
        return sizeNode.execute(this);
    }

    @SuppressWarnings("static-method")
    @ExportMessage(name = "isArrayElementReadable")
    @ExportMessage(name = "isArrayElementModifiable")
    @ExportMessage(name = "isArrayElementInsertable")
    protected boolean isArrayElementReadable(final long index, @Shared("sizeNode") @Cached final NativeObjectSizeNode sizeNode) {
        return 0 <= index && index < sizeNode.execute(this);
    }

    @ExportMessage
    public abstract static class ReadArrayElement {
        @Specialization(guards = "obj.isByteType()")
        protected static final byte doNativeBytes(final NativeObject obj, final long index) {
            return obj.getByte(index);
        }

        @Specialization(guards = "obj.isShortType()")
        protected static final short doNativeShorts(final NativeObject obj, final long index) {
            return obj.getShort(index);
        }

        @Specialization(guards = "obj.isIntType()")
        protected static final int doNativeInts(final NativeObject obj, final long index) {
            return obj.getInt(index);
        }

        @Specialization(guards = "obj.isLongType()")
        protected static final long doNativeLongs(final NativeObject obj, final long index) {
            return obj.getLong(index);
        }
    }

    @ExportMessage
    protected void writeArrayElement(final long index, final Object value,
                    @Exclusive @Cached final WrapToSqueakNode wrapNode,
                    @Cached final NativeObjectWriteNode writeNode) throws InvalidArrayIndexException, UnsupportedTypeException {
        try {
            writeNode.execute(this, index, wrapNode.executeWrap(value));
        } catch (final PrimitiveFailed e) {
            /* NativeObjectWriteNode may throw PrimitiveFailed if value cannot be stored. */
            throw UnsupportedTypeException.create(new Object[]{value}, "Cannot store value in NativeObject");
        } catch (final ArrayIndexOutOfBoundsException e) {
            throw InvalidArrayIndexException.create(index);
        }
    }

    @ExportMessage
    public boolean isString() {
        final ClassObject squeakClass = getSqueakClass();
        return squeakClass.isStringClass() || squeakClass.isSymbolClass() || squeakClass.isWideStringClass();
    }

    @ExportMessage
    public String asString(@Cached("createBinaryProfile()") final ConditionProfile byteStringOrSymbolProfile,
                    @Cached final BranchProfile errorProfile) throws UnsupportedMessageException {
        final ClassObject squeakClass = getSqueakClass();
        if (byteStringOrSymbolProfile.profile(squeakClass.isStringClass() || squeakClass.isSymbolClass())) {
            return asStringUnsafe();
        } else if (squeakClass.isWideStringClass()) {
            return asStringFromWideString();
        } else {
            errorProfile.enter();
            throw UnsupportedMessageException.create();
        }
    }

    public byte[] getStorage() {
        return storage;
    }

    private enum Format {
        BYTES,
        SHORTS,
        INTS,
        LONGS
    }
}
