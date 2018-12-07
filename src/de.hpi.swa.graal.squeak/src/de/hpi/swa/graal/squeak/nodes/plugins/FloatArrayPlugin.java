package de.hpi.swa.graal.squeak.nodes.plugins;

import java.util.Arrays;
import java.util.List;

import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;

import de.hpi.swa.graal.squeak.model.CompiledMethodObject;
import de.hpi.swa.graal.squeak.model.FloatObject;
import de.hpi.swa.graal.squeak.model.NativeObject;
import de.hpi.swa.graal.squeak.model.NilObject;
import de.hpi.swa.graal.squeak.nodes.primitives.AbstractPrimitiveFactoryHolder;
import de.hpi.swa.graal.squeak.nodes.primitives.AbstractPrimitiveNode;
import de.hpi.swa.graal.squeak.nodes.primitives.PrimitiveInterfaces.BinaryPrimitive;
import de.hpi.swa.graal.squeak.nodes.primitives.PrimitiveInterfaces.TernaryPrimitive;
import de.hpi.swa.graal.squeak.nodes.primitives.PrimitiveInterfaces.UnaryPrimitive;
import de.hpi.swa.graal.squeak.nodes.primitives.SqueakPrimitive;

public class FloatArrayPlugin extends AbstractPrimitiveFactoryHolder {

    @Override
    public List<? extends NodeFactory<? extends AbstractPrimitiveNode>> getFactories() {
        return FloatArrayPluginFactory.getFactories();
    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveAddFloatArray") // TODO: implement primitive
    public abstract static class PrimAddFloatArrayNode extends AbstractPrimitiveNode {

        public PrimAddFloatArrayNode(final CompiledMethodObject method) {
            super(method);
        }

    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveAddScalar") // TODO: implement primitive
    public abstract static class PrimAddScalarNode extends AbstractPrimitiveNode {

        public PrimAddScalarNode(final CompiledMethodObject method) {
            super(method);
        }

    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveAt")
    public abstract static class PrimFloatArrayAtNode extends AbstractPrimitiveNode implements BinaryPrimitive {

        public PrimFloatArrayAtNode(final CompiledMethodObject method) {
            super(method);
        }

        @Specialization(guards = "receiver.isIntType()")
        protected static final double doAt(final NativeObject receiver, final long index) {
            return Float.intBitsToFloat(receiver.getIntStorage()[(int) index - 1]);
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveAtPut")
    public abstract static class PrimFloatArrayAtPutNode extends AbstractPrimitiveNode implements TernaryPrimitive {

        public PrimFloatArrayAtPutNode(final CompiledMethodObject method) {
            super(method);
        }

        @Specialization(guards = "receiver.isIntType()")
        protected static final double doDouble(final NativeObject receiver, final long index, final double value) {
            receiver.getIntStorage()[(int) index - 1] = Float.floatToRawIntBits((float) value);
            return value;
        }

        @Specialization
        protected static final double doFloat(final NativeObject receiver, final long index, final FloatObject value) {
            return doDouble(receiver, index, value.getValue());
        }

        @Specialization
        protected static final double doFloat(final NativeObject receiver, final long index, final long value) {
            return doDouble(receiver, index, value);
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveDivFloatArray") // TODO: implement primitive
    public abstract static class PrimDivFloatArrayNode extends AbstractPrimitiveNode {

        public PrimDivFloatArrayNode(final CompiledMethodObject method) {
            super(method);
        }

    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveDivScalar") // TODO: implement primitive
    public abstract static class PrimDivScalarNode extends AbstractPrimitiveNode {

        public PrimDivScalarNode(final CompiledMethodObject method) {
            super(method);
        }

    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveDotProduct") // TODO: implement primitive
    public abstract static class PrimDotProductNode extends AbstractPrimitiveNode {

        public PrimDotProductNode(final CompiledMethodObject method) {
            super(method);
        }

    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveEqual")
    public abstract static class PrimFloatArrayEqualNode extends AbstractPrimitiveNode implements BinaryPrimitive {

        public PrimFloatArrayEqualNode(final CompiledMethodObject method) {
            super(method);
        }

        @Specialization(guards = {"receiver.isIntType()", "other.isIntType()"})
        protected final boolean doEqual(final NativeObject receiver, final NativeObject other) {
            return Arrays.equals(receiver.getIntStorage(), other.getIntStorage()) ? code.image.sqTrue : code.image.sqFalse;
        }

        /*
         * Specialization for quick nil checks.
         */
        @SuppressWarnings("unused")
        @Specialization
        protected final boolean doNilCase(final NativeObject receiver, final NilObject other) {
            return code.image.sqFalse;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveHashArray")
    public abstract static class PrimHashArrayNode extends AbstractPrimitiveNode implements UnaryPrimitive {

        public PrimHashArrayNode(final CompiledMethodObject method) {
            super(method);
        }

        @Specialization(guards = "receiver.isIntType()")
        protected static final long doHash(final NativeObject receiver) {
            final int[] words = receiver.getIntStorage();
            long hash = 0;
            for (int word : words) {
                hash += word;
            }
            return hash & 0x1fffffff;
        }
    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveLength") // TODO: implement primitive
    public abstract static class PrimFloatArrayLengthNode extends AbstractPrimitiveNode {

        public PrimFloatArrayLengthNode(final CompiledMethodObject method) {
            super(method);
        }

    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveMulFloatArray") // TODO: implement primitive
    public abstract static class PrimMulFloatArrayNode extends AbstractPrimitiveNode {

        public PrimMulFloatArrayNode(final CompiledMethodObject method) {
            super(method);
        }

    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveMulScalar") // TODO: implement primitive
    public abstract static class PrimMulScalarNode extends AbstractPrimitiveNode {

        public PrimMulScalarNode(final CompiledMethodObject method) {
            super(method);
        }

    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveNormalize") // TODO: implement primitive
    public abstract static class PrimFloatArrayNormalizeNode extends AbstractPrimitiveNode {

        public PrimFloatArrayNormalizeNode(final CompiledMethodObject method) {
            super(method);
        }

    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveSubFloatArray") // TODO: implement primitive
    public abstract static class PrimSubFloatArrayNode extends AbstractPrimitiveNode {

        public PrimSubFloatArrayNode(final CompiledMethodObject method) {
            super(method);
        }

    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveSubScalar") // TODO: implement primitive
    public abstract static class PrimSubScalarNode extends AbstractPrimitiveNode {

        public PrimSubScalarNode(final CompiledMethodObject method) {
            super(method);
        }

    }

    @GenerateNodeFactory
    @SqueakPrimitive(name = "primitiveSum")
    public abstract static class PrimFloatArraySumNode extends AbstractPrimitiveNode implements UnaryPrimitive {

        public PrimFloatArraySumNode(final CompiledMethodObject method) {
            super(method);
        }

        @Specialization(guards = "receiver.isIntType()")
        protected static final double doSum(final NativeObject receiver) {
            final int[] words = receiver.getIntStorage();
            double sum = 0;
            for (int word : words) {
                sum += Float.intBitsToFloat(word);
            }
            return sum;
        }
    }
}
