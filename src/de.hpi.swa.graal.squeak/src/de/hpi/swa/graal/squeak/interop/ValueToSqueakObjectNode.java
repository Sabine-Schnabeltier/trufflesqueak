package de.hpi.swa.graal.squeak.interop;

import org.graalvm.polyglot.Value;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;

import de.hpi.swa.graal.squeak.exceptions.SqueakExceptions.SqueakException;
import de.hpi.swa.graal.squeak.model.CompiledCodeObject;
import de.hpi.swa.graal.squeak.nodes.AbstractNodeWithCode;

public abstract class ValueToSqueakObjectNode extends AbstractNodeWithCode {

    protected ValueToSqueakObjectNode(final CompiledCodeObject code) {
        super(code);
    }

    public static ValueToSqueakObjectNode create(final CompiledCodeObject code) {
        return ValueToSqueakObjectNodeGen.create(code);
    }

    public abstract Object executeValue(Value value);

    @Specialization(guards = {"value.isNull()"})
    protected final Object doNil(@SuppressWarnings("unused") final Value value) {
        return code.image.nil;
    }

    @Specialization(guards = {"value.isBoolean()"})
    protected final Object doBoolean(final Value value) {
        return code.image.wrap(value.asBoolean());
    }

    @Specialization(guards = {"value.fitsInLong()"})
    protected final Object doLong(final Value value) {
        return code.image.wrap(value.asLong());
    }

    @Specialization(guards = {"value.fitsInDouble()"})
    protected final Object doDouble(final Value value) {
        return code.image.wrap(value.asDouble());
    }

    @Specialization(guards = {"value.isString()"})
    protected final Object doString(final Value value) {
        return code.image.wrap(value.asString());
    }

    @Fallback
    protected static final Object doFail(final Value value) {
        throw new SqueakException("Unexpected Value: ", value);
    }
}
