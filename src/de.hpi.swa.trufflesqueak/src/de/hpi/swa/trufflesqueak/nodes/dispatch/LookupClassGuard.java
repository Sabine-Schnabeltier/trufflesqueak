/*
 * Copyright (c) 2017-2026 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021-2026 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.dispatch;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.nodes.Node;

import de.hpi.swa.trufflesqueak.image.SqueakImageContext;
import de.hpi.swa.trufflesqueak.model.AbstractSqueakObject;
import de.hpi.swa.trufflesqueak.model.AbstractSqueakObjectWithClassAndHash;
import de.hpi.swa.trufflesqueak.model.BlockClosureObject;
import de.hpi.swa.trufflesqueak.model.CharacterObject;
import de.hpi.swa.trufflesqueak.model.ClassObject;
import de.hpi.swa.trufflesqueak.model.ContextObject;
import de.hpi.swa.trufflesqueak.model.FloatObject;
import de.hpi.swa.trufflesqueak.model.NilObject;
import de.hpi.swa.trufflesqueak.nodes.SqueakGuards;

public abstract class LookupClassGuard {
    public enum GuardType {
        NIL, TRUE, FALSE, SMALL_INTEGER, CHARACTER, DOUBLE,
        CONTEXT, BLOCK_CLOSURE, FULL_BLOCK_CLOSURE, FLOAT,
        SQUEAK_OBJECT, FOREIGN
    }

    protected final GuardType type;

    protected LookupClassGuard(final GuardType type) {
        this.type = type;
    }

    /**
     * A monomorphic fast-path for the HotSpot interpreter to avoid megamorphic
     * virtual dispatch overhead. GraalVM will constant-fold this switch during JIT compilation.
     */
    public final boolean fastCheck(final Object receiver) {
        switch (this.type) {
            case NIL: return receiver == NilObject.SINGLETON;
            case TRUE: return receiver == Boolean.TRUE;
            case FALSE: return receiver == Boolean.FALSE;
            case SMALL_INTEGER: return receiver instanceof Long;
            case CHARACTER: return receiver instanceof Character || receiver instanceof CharacterObject;
            case DOUBLE: return receiver instanceof Double;
            case CONTEXT: return receiver instanceof ContextObject;
            case BLOCK_CLOSURE: return receiver instanceof BlockClosureObject closure && closure.isABlockClosure();
            case FULL_BLOCK_CLOSURE: return receiver instanceof BlockClosureObject closure && closure.isAFullBlockClosure();
            case FLOAT: return receiver instanceof FloatObject;
            case SQUEAK_OBJECT:
            case FOREIGN:
            default:
                return this.check(receiver);
        }
    }

    public abstract boolean check(Object receiver);

    public final ClassObject getSqueakClass(final Node node) {
        CompilerAsserts.partialEvaluationConstant(node);
        return getSqueakClassInternal(node);
    }

    protected abstract ClassObject getSqueakClassInternal(Node node);

    @NeverDefault
    public static LookupClassGuard create(final Object receiver) {
        if (receiver == NilObject.SINGLETON) {
            return NilGuard.SINGLETON;
        } else if (receiver == Boolean.TRUE) {
            return TrueGuard.SINGLETON;
        } else if (receiver == Boolean.FALSE) {
            return FalseGuard.SINGLETON;
        } else if (receiver instanceof Long) {
            return SmallIntegerGuard.SINGLETON;
        } else if (receiver instanceof Character || receiver instanceof CharacterObject) {
            return CharacterGuard.SINGLETON;
        } else if (receiver instanceof Double) {
            return DoubleGuard.SINGLETON;
        } else if (receiver instanceof ContextObject) {
            return ContextObjectGuard.SINGLETON;
        } else if (receiver instanceof final BlockClosureObject closure) {
            return closure.isABlockClosure() ? BlockClosureGuard.SINGLETON : FullBlockClosureGuard.SINGLETON;
        } else if (receiver instanceof FloatObject) {
            return FloatObjectGuard.SINGLETON;
        } else if (receiver instanceof final AbstractSqueakObjectWithClassAndHash o) {
            return new AbstractSqueakObjectWithClassAndHashGuard((AbstractSqueakObjectWithClassAndHash) o.resolveForwardingPointer());
        } else {
            assert !(receiver instanceof AbstractSqueakObject);
            return ForeignObjectGuard.SINGLETON;
        }
    }

    private static final class NilGuard extends LookupClassGuard {
        private static final NilGuard SINGLETON = new NilGuard();

        private NilGuard() { super(GuardType.NIL); }

        @Override
        public boolean check(final Object receiver) {
            return receiver == NilObject.SINGLETON;
        }

        @Override
        protected ClassObject getSqueakClassInternal(final Node node) {
            return SqueakImageContext.get(node).nilClass;
        }
    }

    private static final class TrueGuard extends LookupClassGuard {
        private static final TrueGuard SINGLETON = new TrueGuard();

        private TrueGuard() { super(GuardType.TRUE); }

        @Override
        public boolean check(final Object receiver) {
            return receiver == Boolean.TRUE;
        }

        @Override
        protected ClassObject getSqueakClassInternal(final Node node) {
            return SqueakImageContext.get(node).trueClass;
        }
    }

    private static final class FalseGuard extends LookupClassGuard {
        private static final FalseGuard SINGLETON = new FalseGuard();

        private FalseGuard() { super(GuardType.FALSE); }

        @Override
        public boolean check(final Object receiver) {
            return receiver == Boolean.FALSE;
        }

        @Override
        protected ClassObject getSqueakClassInternal(final Node node) {
            return SqueakImageContext.get(node).falseClass;
        }
    }

    private static final class SmallIntegerGuard extends LookupClassGuard {
        private static final SmallIntegerGuard SINGLETON = new SmallIntegerGuard();

        private SmallIntegerGuard() { super(GuardType.SMALL_INTEGER); }

        @Override
        public boolean check(final Object receiver) {
            return receiver instanceof Long;
        }

        @Override
        protected ClassObject getSqueakClassInternal(final Node node) {
            return SqueakImageContext.get(node).smallIntegerClass;
        }
    }

    private static final class CharacterGuard extends LookupClassGuard {
        private static final CharacterGuard SINGLETON = new CharacterGuard();

        private CharacterGuard() { super(GuardType.CHARACTER); }

        @Override
        public boolean check(final Object receiver) {
            return receiver instanceof Character || receiver instanceof CharacterObject;
        }

        @Override
        protected ClassObject getSqueakClassInternal(final Node node) {
            return SqueakImageContext.get(node).characterClass;
        }
    }

    private static final class DoubleGuard extends LookupClassGuard {
        private static final DoubleGuard SINGLETON = new DoubleGuard();

        private DoubleGuard() { super(GuardType.DOUBLE); }

        @Override
        public boolean check(final Object receiver) {
            return receiver instanceof Double;
        }

        @Override
        protected ClassObject getSqueakClassInternal(final Node node) {
            return SqueakImageContext.get(node).smallFloatClass;
        }
    }

    private static final class ContextObjectGuard extends LookupClassGuard {
        private static final ContextObjectGuard SINGLETON = new ContextObjectGuard();

        private ContextObjectGuard() { super(GuardType.CONTEXT); }

        @Override
        public boolean check(final Object receiver) {
            return receiver instanceof ContextObject;
        }

        @Override
        protected ClassObject getSqueakClassInternal(final Node node) {
            return SqueakImageContext.get(node).methodContextClass;
        }
    }

    private static final class BlockClosureGuard extends LookupClassGuard {
        private static final BlockClosureGuard SINGLETON = new BlockClosureGuard();

        private BlockClosureGuard() { super(GuardType.BLOCK_CLOSURE); }

        @Override
        public boolean check(final Object receiver) {
            return receiver instanceof final BlockClosureObject closure && closure.isABlockClosure();
        }

        @Override
        protected ClassObject getSqueakClassInternal(final Node node) {
            return SqueakImageContext.get(node).blockClosureClass;
        }
    }

    private static final class FullBlockClosureGuard extends LookupClassGuard {
        private static final FullBlockClosureGuard SINGLETON = new FullBlockClosureGuard();

        private FullBlockClosureGuard() { super(GuardType.FULL_BLOCK_CLOSURE); }

        @Override
        public boolean check(final Object receiver) {
            return receiver instanceof final BlockClosureObject closure && closure.isAFullBlockClosure();
        }

        @Override
        protected ClassObject getSqueakClassInternal(final Node node) {
            return SqueakImageContext.get(node).getFullBlockClosureClass();
        }
    }

    private static final class FloatObjectGuard extends LookupClassGuard {
        private static final FloatObjectGuard SINGLETON = new FloatObjectGuard();

        private FloatObjectGuard() { super(GuardType.FLOAT); }

        @Override
        public boolean check(final Object receiver) {
            return receiver instanceof FloatObject;
        }

        @Override
        protected ClassObject getSqueakClassInternal(final Node node) {
            return SqueakImageContext.get(node).floatClass;
        }
    }

    private static final class AbstractSqueakObjectWithClassAndHashGuard extends LookupClassGuard {
        private final ClassObject expectedClass;

        private AbstractSqueakObjectWithClassAndHashGuard(final AbstractSqueakObjectWithClassAndHash receiver) {
            super(GuardType.SQUEAK_OBJECT);
            expectedClass = receiver.getSqueakClass();
            assert expectedClass.assertNotForwarded();
        }

        @Override
        public boolean check(final Object receiver) {
            return receiver instanceof final AbstractSqueakObjectWithClassAndHash o && o.getSqueakClass() == expectedClass;
        }

        @Override
        protected ClassObject getSqueakClassInternal(final Node node) {
            return expectedClass;
        }
    }

    private static final class ForeignObjectGuard extends LookupClassGuard {
        private static final ForeignObjectGuard SINGLETON = new ForeignObjectGuard();

        private ForeignObjectGuard() { super(GuardType.FOREIGN); }

        @Override
        public boolean check(final Object receiver) {
            return !SqueakGuards.isAbstractSqueakObject(receiver) && !SqueakGuards.isUsedJavaPrimitive(receiver);
        }

        @Override
        protected ClassObject getSqueakClassInternal(final Node node) {
            final SqueakImageContext image = SqueakImageContext.get(node);
            if (!image.getForeignObjectClassStableAssumption().isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
            }
            return image.getForeignObjectClass();
        }
    }
}
