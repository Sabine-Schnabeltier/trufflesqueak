/*
 * Copyright (c) 2017-2020 Software Architecture Group, Hasso Plattner Institute
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.bytecodes;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.profiles.ConditionProfile;

import de.hpi.swa.trufflesqueak.SqueakLanguage;
import de.hpi.swa.trufflesqueak.exceptions.Returns.NonLocalReturn;
import de.hpi.swa.trufflesqueak.exceptions.Returns.NonVirtualReturn;
import de.hpi.swa.trufflesqueak.image.SqueakImageContext;
import de.hpi.swa.trufflesqueak.model.AbstractPointersObject;
import de.hpi.swa.trufflesqueak.model.AbstractSqueakObjectWithClassAndHash;
import de.hpi.swa.trufflesqueak.model.BlockClosureObject;
import de.hpi.swa.trufflesqueak.model.CharacterObject;
import de.hpi.swa.trufflesqueak.model.ClassObject;
import de.hpi.swa.trufflesqueak.model.CompiledCodeObject;
import de.hpi.swa.trufflesqueak.model.ContextObject;
import de.hpi.swa.trufflesqueak.model.FloatObject;
import de.hpi.swa.trufflesqueak.model.NativeObject;
import de.hpi.swa.trufflesqueak.model.NilObject;
import de.hpi.swa.trufflesqueak.model.layout.ObjectLayout;
import de.hpi.swa.trufflesqueak.nodes.AbstractNode;
import de.hpi.swa.trufflesqueak.nodes.DispatchSendFromStackNode;
import de.hpi.swa.trufflesqueak.nodes.LookupMethodForSelectorNode;
import de.hpi.swa.trufflesqueak.nodes.accessing.AbstractPointersObjectNodes.AbstractPointersObjectReadNode;
import de.hpi.swa.trufflesqueak.nodes.bytecodes.SendBytecodesFactory.LookupClassNodeGen;
import de.hpi.swa.trufflesqueak.nodes.bytecodes.SendBytecodesFactory.LookupSuperClassNodeGen;
import de.hpi.swa.trufflesqueak.nodes.context.frame.FrameSlotReadNode;
import de.hpi.swa.trufflesqueak.nodes.context.frame.FrameStackPushNode;
import de.hpi.swa.trufflesqueak.nodes.primitives.impl.ControlPrimitives.PrimExitToDebuggerNode;
import de.hpi.swa.trufflesqueak.util.FrameAccess;

public final class SendBytecodes {
    public abstract static class AbstractSendNode extends AbstractInstrumentableBytecodeNode {
        protected final NativeObject selector;
        private final int argumentCount;

        @Child private AbstractLookupClassNode lookupClassNode;
        @Child private LookupMethodForSelectorNode lookupMethodNode;
        @Child private DispatchSendFromStackNode dispatchSendNode;
        @Child private FrameSlotReadNode peekReceiverNode;
        @Child private FrameStackPushNode pushNode;

        private final ConditionProfile nlrProfile = ConditionProfile.createBinaryProfile();
        private final ConditionProfile nvrProfile = ConditionProfile.createBinaryProfile();

        private AbstractSendNode(final CompiledCodeObject code, final int index, final int numBytecodes, final Object sel, final int argcount) {
            this(code, index, numBytecodes, sel, argcount, LookupClassNodeGen.create());
        }

        private AbstractSendNode(final CompiledCodeObject code, final int index, final int numBytecodes, final Object sel, final int argcount, final AbstractLookupClassNode lookupClassNode) {
            super(code, index, numBytecodes);
            selector = (NativeObject) sel;
            argumentCount = argcount;
            this.lookupClassNode = lookupClassNode;
            lookupMethodNode = LookupMethodForSelectorNode.create(selector);
            dispatchSendNode = DispatchSendFromStackNode.create(selector, code, argumentCount);
        }

        protected AbstractSendNode(final AbstractSendNode original) {
            this(original.code, original.index, original.numBytecodes, original.selector, original.argumentCount);
        }

        @Override
        public final void executeVoid(final VirtualFrame frame) {
            final Object receiver = getReceiver(frame);
            final ClassObject receiverClass = lookupClassNode.executeLookup(receiver);
            final Object lookupResult = lookupMethodNode.executeLookup(receiverClass);
            try {
                final Object result = dispatchSendNode.executeSend(frame, selector, lookupResult, receiver, receiverClass);
                assert result != null : "Result of a message send should not be null";
                getPushNode().execute(frame, result);
            } catch (final NonLocalReturn nlr) {
                if (nlrProfile.profile(nlr.getTargetContextOrMarker() == getMarker(frame) || nlr.getTargetContextOrMarker() == getContext(frame))) {
                    getPushNode().execute(frame, nlr.getReturnValue());
                } else {
                    throw nlr;
                }
            } catch (final NonVirtualReturn nvr) {
                if (nvrProfile.profile(nvr.getTargetContext() == getContext(frame))) {
                    getPushNode().execute(frame, nvr.getReturnValue());
                } else {
                    throw nvr;
                }
            }
        }

        private Object getReceiver(final VirtualFrame frame) {
            if (peekReceiverNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                final int stackPointer = FrameAccess.getStackPointer(frame, code) - 1 - argumentCount;
                peekReceiverNode = insert(FrameSlotReadNode.create(code.getStackSlot(stackPointer)));
            }
            return peekReceiverNode.executeRead(frame);
        }

        private FrameStackPushNode getPushNode() {
            if (pushNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                pushNode = insert(FrameStackPushNode.create());
            }
            return pushNode;
        }

        public final Object getSelector() {
            return selector;
        }

        @Override
        public final boolean hasTag(final Class<? extends Tag> tag) {
            if (tag == StandardTags.CallTag.class) {
                return true;
            }
            if (tag == DebuggerTags.AlwaysHalt.class) {
                return PrimExitToDebuggerNode.SELECTOR_NAME.equals(selector.asStringUnsafe());
            }
            return super.hasTag(tag);
        }

        @Override
        public String toString() {
            CompilerAsserts.neverPartOfCompilation();
            return "send: " + selector.asStringUnsafe();
        }
    }

    protected abstract static class AbstractLookupClassNode extends AbstractNode {
        protected abstract ClassObject executeLookup(Object receiver);
    }

    protected abstract static class LookupClassNode extends AbstractLookupClassNode {
        protected static final int LOOKUP_CACHE_SIZE = 6;

        @Specialization
        protected static final ClassObject doNil(@SuppressWarnings("unused") final NilObject value,
                        @Shared("image") @CachedContext(SqueakLanguage.class) final SqueakImageContext image) {
            return image.nilClass;
        }

        @Specialization(guards = "value == TRUE")
        protected static final ClassObject doTrue(@SuppressWarnings("unused") final boolean value,
                        @Shared("image") @CachedContext(SqueakLanguage.class) final SqueakImageContext image) {
            return image.trueClass;
        }

        @Specialization(guards = "value != TRUE")
        protected static final ClassObject doFalse(@SuppressWarnings("unused") final boolean value,
                        @Shared("image") @CachedContext(SqueakLanguage.class) final SqueakImageContext image) {
            return image.falseClass;
        }

        @Specialization
        protected static final ClassObject doSmallInteger(@SuppressWarnings("unused") final long value,
                        @Shared("image") @CachedContext(SqueakLanguage.class) final SqueakImageContext image) {
            return image.smallIntegerClass;
        }

        @Specialization
        protected static final ClassObject doChar(@SuppressWarnings("unused") final char value,
                        @Shared("image") @CachedContext(SqueakLanguage.class) final SqueakImageContext image) {
            return image.characterClass;
        }

        @Specialization
        protected static final ClassObject doDouble(@SuppressWarnings("unused") final double value,
                        @Shared("image") @CachedContext(SqueakLanguage.class) final SqueakImageContext image) {
            return image.getSmallFloatClass();
        }

        @Specialization
        protected static final ClassObject doClosure(@SuppressWarnings("unused") final BlockClosureObject value,
                        @Shared("image") @CachedContext(SqueakLanguage.class) final SqueakImageContext image) {
            return image.blockClosureClass;
        }

        @Specialization
        protected static final ClassObject doCharacter(@SuppressWarnings("unused") final CharacterObject value,
                        @Shared("image") @CachedContext(SqueakLanguage.class) final SqueakImageContext image) {
            return image.characterClass;
        }

        @Specialization
        protected static final ClassObject doContext(@SuppressWarnings("unused") final ContextObject value,
                        @Shared("image") @CachedContext(SqueakLanguage.class) final SqueakImageContext image) {
            return image.methodContextClass;
        }

        @Specialization
        protected static final ClassObject doFloat(@SuppressWarnings("unused") final FloatObject value,
                        @Shared("image") @CachedContext(SqueakLanguage.class) final SqueakImageContext image) {
            return image.floatClass;
        }

        @Specialization(guards = "value.getLayout() == cachedLayout", limit = "LOOKUP_CACHE_SIZE")
        protected static final ClassObject doAbstractPointersObjectCached(@SuppressWarnings("unused") final AbstractPointersObject value,
                        @Cached("value.getLayout()") final ObjectLayout cachedLayout) {
            return cachedLayout.getSqueakClass();
        }

        @Specialization(guards = "value.getSqueakClass() == cachedClass", replaces = "doAbstractPointersObjectCached", limit = "LOOKUP_CACHE_SIZE")
        protected static final ClassObject doAbstractSqueakObjectWithClassAndHashCached(@SuppressWarnings("unused") final AbstractSqueakObjectWithClassAndHash value,
                        @Cached("value.getSqueakClass()") final ClassObject cachedClass) {
            return cachedClass;
        }

        @Specialization(replaces = {"doAbstractPointersObjectCached", "doAbstractSqueakObjectWithClassAndHashCached"})
        protected static final ClassObject doAbstractSqueakObjectWithClassAndHash(final AbstractSqueakObjectWithClassAndHash value) {
            return value.getSqueakClass();
        }

        @Specialization(guards = {"!isAbstractSqueakObject(value)", "!isUsedJavaPrimitive(value)"})
        protected static final ClassObject doForeignObject(@SuppressWarnings("unused") final Object value,
                        @Shared("image") @CachedContext(SqueakLanguage.class) final SqueakImageContext image) {
            return image.getForeignObjectClass();
        }
    }

    protected abstract static class LookupSuperClassNode extends AbstractLookupClassNode {
        @Child private AbstractPointersObjectReadNode readNode = AbstractPointersObjectReadNode.create();
        protected final ClassObject methodClass;

        protected LookupSuperClassNode(final CompiledCodeObject code) {
            /* Literals are considered to be constant, so methodClass should be constant. */
            methodClass = code.getMethod().getMethodClassSlow();
        }

        @Specialization(assumptions = "methodClass.getClassHierarchyStable()")
        protected static final ClassObject doCached(@SuppressWarnings("unused") final Object receiver,
                        @Cached("methodClass.getSuperclassOrNull()") final ClassObject superclass) {
            return superclass;
        }
    }

    public static final class SecondExtendedSendNode extends AbstractSendNode {
        public SecondExtendedSendNode(final CompiledCodeObject code, final int index, final int numBytecodes, final byte param) {
            super(code, index, numBytecodes, code.getLiteral(param & 63), Byte.toUnsignedInt(param) >> 6);
        }
    }

    public static final class SendLiteralSelectorNode extends AbstractSendNode {
        public SendLiteralSelectorNode(final CompiledCodeObject code, final int index, final int numBytecodes, final Object selector, final int argCount) {
            super(code, index, numBytecodes, selector, argCount);
        }

        public static AbstractInstrumentableBytecodeNode create(final CompiledCodeObject code, final int index, final int numBytecodes, final int literalIndex, final int argCount) {
            final Object selector = code.getLiteral(literalIndex);
            return new SendLiteralSelectorNode(code, index, numBytecodes, selector, argCount);
        }
    }

    public static final class SendSpecialSelectorNode extends AbstractSendNode {
        private SendSpecialSelectorNode(final CompiledCodeObject code, final int index, final int numBytecodes, final Object selector, final int argcount) {
            super(code, index, numBytecodes, selector, argcount);
        }

        public static SendSpecialSelectorNode create(final CompiledCodeObject code, final int index, final int selectorIndex) {
            final NativeObject specialSelector = code.image.getSpecialSelector(selectorIndex);
            final int numArguments = code.image.getSpecialSelectorNumArgs(selectorIndex);
            return new SendSpecialSelectorNode(code, index, 1, specialSelector, numArguments);
        }
    }

    public static final class SendSelfSelectorNode extends AbstractSendNode {
        public SendSelfSelectorNode(final CompiledCodeObject code, final int index, final int numBytecodes, final Object selector, final int numArgs) {
            super(code, index, numBytecodes, selector, numArgs);
        }
    }

    public static final class SingleExtendedSendNode extends AbstractSendNode {
        public SingleExtendedSendNode(final CompiledCodeObject code, final int index, final int numBytecodes, final byte param) {
            super(code, index, numBytecodes, code.getLiteral(param & 31), Byte.toUnsignedInt(param) >> 5);
        }
    }

    public static final class SingleExtendedSuperNode extends AbstractSendNode {

        public SingleExtendedSuperNode(final CompiledCodeObject code, final int index, final int numBytecodes, final byte param) {
            this(code, index, numBytecodes, param & 31, Byte.toUnsignedInt(param) >> 5);
        }

        public SingleExtendedSuperNode(final CompiledCodeObject code, final int index, final int numBytecodes, final int literalIndex, final int numArgs) {
            super(code, index, numBytecodes, code.getLiteral(literalIndex), numArgs, LookupSuperClassNodeGen.create(code));
        }

        @Override
        public String toString() {
            CompilerAsserts.neverPartOfCompilation();
            return "sendSuper: " + selector.asStringUnsafe();
        }
    }
}
