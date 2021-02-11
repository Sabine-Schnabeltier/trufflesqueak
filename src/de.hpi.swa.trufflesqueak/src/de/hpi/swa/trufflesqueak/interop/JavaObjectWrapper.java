/*
 * Copyright (c) 2017-2021 Software Architecture Group, Hasso Plattner Institute
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.interop;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.dsl.UnsupportedSpecializationException;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;

import de.hpi.swa.trufflesqueak.model.BooleanObject;
import de.hpi.swa.trufflesqueak.model.NilObject;
import de.hpi.swa.trufflesqueak.nodes.SqueakGuards;

@SuppressWarnings("static-method")
@ExportLibrary(InteropLibrary.class)
public final class JavaObjectWrapper implements TruffleObject {
    protected static final int LIMIT = 2;

    protected final Object wrappedObject;
    private InteropArray cachedMembers;
    private HashMap<String, Field> fields;
    private HashMap<String, Method> methods;

    private JavaObjectWrapper(final Object object) {
        wrappedObject = object;
    }

    @TruffleBoundary
    public static Object wrap(final Object object) {
        if (object == null) {
            return NilObject.SINGLETON;
        } else if (SqueakGuards.isUsedJavaPrimitive(object) || object instanceof JavaObjectWrapper) {
            return object;
        } else {
            return new JavaObjectWrapper(object);
        }
    }

    private HashMap<String, Field> getFields() {
        if (fields == null) {
            final Class<?> clazz = wrappedObject.getClass();
            final Field[] fieldsArray = clazz.getFields();
            fields = new HashMap<>(fieldsArray.length);
            for (final Field field : fieldsArray) {
                fields.put(field.getName(), field);
            }
        }
        return fields;
    }

    private HashMap<String, Method> getMethods() {
        if (methods == null) {
            final Class<?> clazz = wrappedObject.getClass();
            final Method[] methodsArray = clazz.getMethods();
            methods = new HashMap<>(methodsArray.length);
            for (final Method method : methodsArray) {
                methods.put(method.getName(), method);
            }
        }
        return methods;
    }

    protected boolean isClass() {
        return wrappedObject instanceof Class<?>;
    }

    protected boolean isArrayClass() {
        return isClass() && asClass().isArray();
    }

    protected boolean isDefaultClass() {
        return isClass() && !asClass().isArray();
    }

    private Class<?> asClass() {
        assert isClass();
        return (Class<?>) wrappedObject;
    }

    private Class<?> getObjectClass() {
        return wrappedObject.getClass();
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(wrappedObject);
    }

    @Override
    public boolean equals(final Object other) {
        if (other instanceof JavaObjectWrapper) {
            return wrappedObject == ((JavaObjectWrapper) other).wrappedObject;
        }
        return false;
    }

    @Override
    public String toString() {
        if (isClass()) {
            return "JavaClass[" + asClass().getTypeName() + "]";
        }
        return "JavaObject[" + wrappedObject + " (" + getObjectClass().getTypeName() + ")" + "]";
    }

    @ExportMessage
    @TruffleBoundary
    protected Object readMember(final String member) throws UnknownIdentifierException {
        final Field field = getFields().get(member);
        if (field != null) {
            try {
                return wrap(field.get(wrappedObject));
            } catch (IllegalArgumentException | IllegalAccessException e) {
                throw UnknownIdentifierException.create(member);
            }
        }
        final Method method = getMethods().get(member);
        if (method != null) {
            return new JavaMethodWrapper(wrappedObject, method);
        } else {
            throw UnknownIdentifierException.create(member);
        }
    }

    @ExportMessage
    protected Object getMembers(@SuppressWarnings("unused") final boolean includeInternal) {
        if (cachedMembers == null) {
            cachedMembers = calculateMembers();
        }
        return cachedMembers;
    }

    @TruffleBoundary
    private InteropArray calculateMembers() {
        final HashSet<String> members = new HashSet<>(getFields().keySet());
        members.addAll(getMethods().keySet());
        return new InteropArray(members.toArray(new String[0]));
    }

    @ExportMessage
    protected boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    protected boolean isMemberReadable(final String member) {
        return getFields().containsKey(member) || getMethods().containsKey(member);
    }

    @ExportMessage
    @ExportMessage(name = "isMemberInsertable")
    protected boolean isMemberModifiable(@SuppressWarnings("unused") final String member) {
        return false;
    }

    @ExportMessage
    @TruffleBoundary
    protected boolean isMemberInvocable(final String member) {
        return getMethods().containsKey(member);
    }

    @ExportMessage
    @TruffleBoundary
    @SuppressWarnings("deprecation") // isAccessible deprecated in Java 11
    protected Object invokeMember(final String member, final Object... arguments) throws UnknownIdentifierException, UnsupportedTypeException {
        final Method method = getMethods().get(member);
        if (method != null) {
            try {
                if (!method.isAccessible()) {
                    method.setAccessible(true);
                }
                return wrap(method.invoke(wrappedObject, arguments));
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw UnsupportedTypeException.create(arguments);
            }
        } else {
            throw UnknownIdentifierException.create(member);
        }
    }

    @ExportMessage
    @TruffleBoundary
    @SuppressWarnings("deprecation") // isAccessible deprecated in Java 11
    protected void writeMember(final String key, final Object value) {
        final Field field = getFields().get(key);
        if (field != null) {
            try {
                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }
                field.set(wrappedObject, value instanceof JavaObjectWrapper ? ((JavaObjectWrapper) value).wrappedObject : value);
            } catch (IllegalArgumentException | IllegalAccessException e) {
                throw new UnsupportedOperationException(e);
            }
        } else {
            throw new UnsupportedOperationException(wrappedObject + " has not member " + key);
        }
    }

    @ExportMessage
    protected boolean isNull(@Shared("lib") @CachedLibrary(limit = "LIMIT") final InteropLibrary lib) {
        return lib.isNull(wrappedObject);
    }

    @ExportMessage
    protected boolean isNumber() {
        final Class<?> c = wrappedObject.getClass();
        return c == Byte.class || c == Short.class || c == Integer.class || c == Long.class || c == Float.class || c == Double.class;
    }

    @ExportMessage
    protected boolean fitsInByte(@Shared("lib") @CachedLibrary(limit = "LIMIT") final InteropLibrary lib) {
        if (isNumber()) {
            return lib.fitsInByte(wrappedObject);
        } else {
            return false;
        }
    }

    @ExportMessage
    protected boolean fitsInShort(@Shared("lib") @CachedLibrary(limit = "LIMIT") final InteropLibrary lib) {
        if (isNumber()) {
            return lib.fitsInShort(wrappedObject);
        } else {
            return false;
        }
    }

    @ExportMessage
    protected boolean fitsInInt(@Shared("lib") @CachedLibrary(limit = "LIMIT") final InteropLibrary lib) {
        if (isNumber()) {
            return lib.fitsInInt(wrappedObject);
        } else {
            return false;
        }
    }

    @ExportMessage
    protected boolean fitsInLong(@Shared("lib") @CachedLibrary(limit = "LIMIT") final InteropLibrary lib) {
        if (isNumber()) {
            return lib.fitsInLong(wrappedObject);
        } else {
            return false;
        }
    }

    @ExportMessage
    protected boolean fitsInFloat(@Shared("lib") @CachedLibrary(limit = "LIMIT") final InteropLibrary lib) {
        if (isNumber()) {
            return lib.fitsInFloat(wrappedObject);
        } else {
            return false;
        }
    }

    @ExportMessage
    protected boolean fitsInDouble(@Shared("lib") @CachedLibrary(limit = "LIMIT") final InteropLibrary lib) {
        if (isNumber()) {
            return lib.fitsInDouble(wrappedObject);
        } else {
            return false;
        }
    }

    @ExportMessage
    protected byte asByte(@Shared("lib") @CachedLibrary(limit = "LIMIT") final InteropLibrary lib) throws UnsupportedMessageException {
        if (isNumber()) {
            return lib.asByte(wrappedObject);
        } else {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    protected short asShort(@Shared("lib") @CachedLibrary(limit = "LIMIT") final InteropLibrary lib) throws UnsupportedMessageException {
        if (isNumber()) {
            return lib.asShort(wrappedObject);
        } else {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    protected int asInt(@Shared("lib") @CachedLibrary(limit = "LIMIT") final InteropLibrary lib) throws UnsupportedMessageException {
        if (isNumber()) {
            return lib.asInt(wrappedObject);
        } else {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    protected long asLong(@Shared("lib") @CachedLibrary(limit = "LIMIT") final InteropLibrary lib) throws UnsupportedMessageException {
        if (isNumber()) {
            return lib.asLong(wrappedObject);
        } else {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    protected float asFloat(@Shared("lib") @CachedLibrary(limit = "LIMIT") final InteropLibrary lib) throws UnsupportedMessageException {
        if (isNumber()) {
            return lib.asFloat(wrappedObject);
        } else {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    protected double asDouble(@Shared("lib") @CachedLibrary(limit = "LIMIT") final InteropLibrary lib) throws UnsupportedMessageException {
        if (isNumber()) {
            return lib.asDouble(wrappedObject);
        } else {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    protected boolean isInstantiable() {
        return isClass();
    }

    @ExportMessage
    protected static class Instantiate {

        @Specialization(guards = "!receiver.isClass()")
        @SuppressWarnings("unused")
        protected static final Object doUnsupported(final JavaObjectWrapper receiver, final Object[] args) throws UnsupportedMessageException {
            throw UnsupportedMessageException.create();
        }

        @TruffleBoundary
        @Specialization(guards = "receiver.isArrayClass()")
        protected static final Object doArrayCached(final JavaObjectWrapper receiver, final Object[] args,
                        @CachedLibrary(limit = "1") final InteropLibrary lib) throws UnsupportedMessageException, UnsupportedTypeException, ArityException {
            if (args.length != 1) {
                throw ArityException.create(1, args.length);
            }
            final Object arg0 = args[0];
            final int length;
            if (lib.fitsInInt(arg0)) {
                length = lib.asInt(arg0);
            } else {
                throw UnsupportedTypeException.create(args);
            }
            return wrap(Array.newInstance(receiver.asClass().getComponentType(), length));
        }

        @TruffleBoundary
        @Specialization(guards = "receiver.isDefaultClass()")
        protected static final Object doObjectCached(final JavaObjectWrapper receiver, final Object[] args) throws UnsupportedTypeException {
            assert !receiver.isArrayClass();
            iterateConstructors: for (final Constructor<?> constructor : receiver.asClass().getConstructors()) {
                if (constructor.getParameterCount() == args.length) {
                    for (int i = 0; i < args.length; i++) {
                        if (!constructor.getParameterTypes()[i].isAssignableFrom(args[i].getClass())) {
                            continue iterateConstructors;
                        }
                    }
                    // Arguments should fit into constructor.
                    try {
                        return wrap(constructor.newInstance(args));
                    } catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                        throw UnsupportedTypeException.create(args);
                    }
                }
            }
            throw UnsupportedTypeException.create(args);
        }
    }

    @ExportMessage
    protected boolean isString() {
        return wrappedObject instanceof String;
    }

    @ExportMessage
    protected String asString() throws UnsupportedMessageException {
        try {
            return (String) wrappedObject;
        } catch (final ClassCastException e) {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    @TruffleBoundary
    protected boolean hasArrayElements(@Shared("lib") @CachedLibrary(limit = "LIMIT") final InteropLibrary lib) {
        return wrappedObject.getClass().isArray() || wrappedObject instanceof TruffleObject && lib.hasArrayElements(wrappedObject);
    }

    @ExportMessage
    @ExportMessage(name = "isArrayElementModifiable")
    @TruffleBoundary
    protected boolean isArrayElementReadable(final long index, @Shared("sizeNode") @Cached final ArraySizeNode sizeNode) {
        try {
            return 0 <= index && index < sizeNode.execute(wrappedObject);
        } catch (final UnsupportedSpecializationException | UnsupportedMessageException e) {
            return false;
        }
    }

    @ExportMessage
    protected boolean isArrayElementInsertable(@SuppressWarnings("unused") final long index) {
        return false;
    }

    @ExportMessage
    @TruffleBoundary
    protected long getArraySize(@Shared("sizeNode") @Cached final ArraySizeNode sizeNode) throws UnsupportedMessageException {
        try {
            return sizeNode.execute(wrappedObject);
        } catch (final UnsupportedSpecializationException e) {
            throw UnsupportedMessageException.create();
        }
    }

    @GenerateUncached
    protected abstract static class ArraySizeNode extends Node {
        protected abstract int execute(Object object) throws UnsupportedSpecializationException, UnsupportedMessageException;

        @Specialization
        protected static final int doBoolean(final boolean[] object) {
            return object.length;
        }

        @Specialization
        protected static final int doByte(final byte[] object) {
            return object.length;
        }

        @Specialization
        protected static final int doChar(final char[] object) {
            return object.length;
        }

        @Specialization
        protected static final int doShort(final short[] object) {
            return object.length;
        }

        @Specialization
        protected static final int doInteger(final int[] object) {
            return object.length;
        }

        @Specialization
        protected static final int doLong(final long[] object) {
            return object.length;
        }

        @Specialization
        protected static final int doFloat(final float[] object) {
            return object.length;
        }

        @Specialization
        protected static final int doDouble(final double[] object) {
            return object.length;
        }

        @Specialization
        protected static final int doObject(final Object[] object) {
            return object.length;
        }

        @Specialization(limit = "1")
        protected static final int doTruffleObject(final TruffleObject object, @CachedLibrary("object") final InteropLibrary lib) throws UnsupportedMessageException {
            return (int) lib.getArraySize(object);
        }
    }

    @ExportMessage
    protected Object readArrayElement(final long index, @Cached final ReadArrayElementNode readNode) throws InvalidArrayIndexException, UnsupportedMessageException {
        try {
            return readNode.execute(wrappedObject, (int) index);
        } catch (final ArrayIndexOutOfBoundsException e) {
            throw InvalidArrayIndexException.create(index);
        } catch (final UnsupportedSpecializationException e) {
            throw UnsupportedMessageException.create();
        }
    }

    @GenerateUncached
    protected abstract static class ReadArrayElementNode extends Node {
        protected abstract Object execute(Object object, int index) throws UnsupportedMessageException, InvalidArrayIndexException;

        @Specialization
        protected static final boolean doBoolean(final boolean[] object, final int index) {
            return BooleanObject.wrap(object[index]);
        }

        @Specialization
        protected static final byte doByte(final byte[] object, final int index) {
            return object[index];
        }

        @Specialization
        protected static final char doChar(final char[] object, final int index) {
            return object[index];
        }

        @Specialization
        protected static final long doShort(final short[] object, final int index) {
            return object[index];
        }

        @Specialization
        protected static final long doInteger(final int[] object, final int index) {
            return object[index];
        }

        @Specialization
        protected static final long doLong(final long[] object, final int index) {
            return object[index];
        }

        @Specialization
        protected static final double doFloat(final float[] object, final int index) {
            return object[index];
        }

        @Specialization
        protected static final double doDouble(final double[] object, final int index) {
            return object[index];
        }

        @Specialization
        protected static final Object doObject(final Object[] object, final int index) {
            return wrap(object[index]);
        }

        @Specialization(limit = "1")
        protected static final Object doTruffleObject(final TruffleObject object, final int index, @CachedLibrary("object") final InteropLibrary lib)
                        throws UnsupportedMessageException, InvalidArrayIndexException {
            return lib.readArrayElement(object, index);
        }
    }

    @ExportMessage
    protected void writeArrayElement(final long index, final Object value, @Cached final WriteArrayElementNode writeNode)
                    throws InvalidArrayIndexException, UnsupportedMessageException, UnsupportedTypeException {
        try {
            writeNode.execute(wrappedObject, (int) index, value);
        } catch (final ArrayIndexOutOfBoundsException e) {
            throw InvalidArrayIndexException.create(index);
        } catch (final UnsupportedSpecializationException e) {
            throw UnsupportedMessageException.create();
        }
    }

    @GenerateUncached
    protected abstract static class WriteArrayElementNode extends Node {
        protected abstract void execute(Object object, int index, Object value) throws UnsupportedMessageException, InvalidArrayIndexException, UnsupportedTypeException;

        @Specialization
        protected static final void doBoolean(final boolean[] object, final int index, final boolean value) {
            object[index] = value;
        }

        @Specialization
        protected static final void doByte(final byte[] object, final int index, final byte value) {
            object[index] = value;
        }

        @Specialization
        protected static final void doChar(final char[] object, final int index, final char value) {
            object[index] = value;
        }

        @Specialization
        protected static final void doShort(final short[] object, final int index, final short value) {
            object[index] = value;
        }

        @Specialization
        protected static final void doInteger(final int[] object, final int index, final int value) {
            object[index] = value;
        }

        @Specialization
        protected static final void doLong(final long[] object, final int index, final long value) {
            object[index] = value;
        }

        @Specialization
        protected static final void doFloat(final float[] object, final int index, final float value) {
            object[index] = value;
        }

        @Specialization
        protected static final void doDouble(final double[] object, final int index, final double value) {
            object[index] = value;
        }

        @Specialization
        protected static final void doObject(final Object[] object, final int index, final Object value) {
            object[index] = value;
        }

        @Specialization(limit = "1")
        protected static final void doTruffleObject(final TruffleObject object, final int index, final Object value, @CachedLibrary("object") final InteropLibrary lib)
                        throws UnsupportedMessageException, InvalidArrayIndexException, UnsupportedTypeException {
            lib.writeArrayElement(object, index, value);
        }
    }
}
