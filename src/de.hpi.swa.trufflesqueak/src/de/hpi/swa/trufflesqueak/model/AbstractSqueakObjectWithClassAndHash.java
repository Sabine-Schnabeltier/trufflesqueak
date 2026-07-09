/*
 * Copyright (c) 2017-2026 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021-2026 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.model;

import org.graalvm.collections.UnmodifiableEconomicMap;

import com.oracle.truffle.api.CompilerDirectives;

import de.hpi.swa.trufflesqueak.image.SqueakImageChunk;
import de.hpi.swa.trufflesqueak.image.SqueakImageConstants.ObjectHeader;
import de.hpi.swa.trufflesqueak.image.SqueakImageContext;
import de.hpi.swa.trufflesqueak.util.MiscUtils;

public abstract class AbstractSqueakObjectWithClassAndHash extends AbstractSqueakObjectWithHash {
    /**
     * Spur uses an 64-bit object header (see {@link ObjectHeader}). In TruffleSqueak, we only care
     * about the hash, the class, and a few bits (e.g., isImmutable). Instead of storing the
     * original object header, we directly reference the class, which avoids additional class table
     * lookups. The 22-bit hash is stored in an {@code int} field, the remaining 10 bits are more
     * than enough to encode additional information (e.g., marking state for {@code #allInstances}
     * et al.). The JVM and GraalVM Native Image compress pointers by default, so these two fields
     * can be represented by just one 64-bit word.
     */
    private AbstractSqueakObjectWithClassAndHash squeakClass;

    // For special/well-known objects only.
    protected AbstractSqueakObjectWithClassAndHash() {
        /* Flags are zero and hash is uninitialized. squeakClass is null. */
        super();
    }

    @SuppressWarnings("this-escape")
    protected AbstractSqueakObjectWithClassAndHash(final SqueakImageChunk chunk) {
        initializeFrom(chunk);
    }

    protected AbstractSqueakObjectWithClassAndHash(final ClassObject klass) {
        /* Flags are zero and hash is uninitialized. */
        super();
        squeakClass = klass;
    }

    @SuppressWarnings("this-escape")
    protected AbstractSqueakObjectWithClassAndHash(final AbstractSqueakObjectWithClassAndHash original) {
        super(original);
        squeakClass = original.squeakClass;
        setSqueakHash(HASH_UNINITIALIZED);
    }

    @Override
    public void initializeFrom(final SqueakImageChunk chunk) {
        super.initializeFrom(chunk);
        squeakClass = chunk.getSqueakClass();
    }

    @Override
    public final ClassObject getSqueakClass() {
        return (ClassObject) squeakClass;
    }

    @Override
    public final ClassObject getSqueakClass(final SqueakImageContext image) {
        return getSqueakClass();
    }

    public final boolean needsSqueakClass() {
        return squeakClass == null;
    }

    public final String getSqueakClassName() {
        return getSqueakClass().getClassName();
    }

    public final void setSqueakClass(final ClassObject newClass) {
        squeakClass = newClass;
    }

    public final void becomeOtherClass(final AbstractSqueakObjectWithClassAndHash other) {
        final ClassObject otherSqClass = other.getSqueakClass();
        other.setSqueakClass(getSqueakClass());
        setSqueakClass(otherSqClass);
    }

    public final boolean hasFormatOf(final ClassObject other) {
        return getSqueakClass().getFormat() == other.getFormat();
    }

    @Override
    public void pointersBecomeOneWay(final UnmodifiableEconomicMap<Object, Object> fromToMap) {
        final Object replacement = fromToMap.get(getSqueakClass());
        if (replacement != null) {
            setSqueakClass((ClassObject) replacement);
        }
    }
}
