package de.hpi.swa.trufflesqueak.model;

import java.util.Arrays;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

public class CompiledBlockObject extends CompiledCodeObject {
    @CompilationFinal private final CompiledMethodObject outerMethod;
    @CompilationFinal private final int numCopiedValues;
    @CompilationFinal private final int offset;

    public static CompiledBlockObject create(CompiledCodeObject code, int numArgs, int numCopied, int bytecodeOffset, int blockSize) {
        return new CompiledBlockObject(code, numArgs, numCopied, bytecodeOffset, blockSize);
    }

    private CompiledBlockObject(CompiledCodeObject code, int numArgs, int numCopied, int bytecodeOffset, int blockSize) {
        super(code.image);
        outerMethod = code.getMethod();
        numCopiedValues = numCopied;
        this.offset = bytecodeOffset;
        Object[] outerLiterals = outerMethod.getLiterals();
        Object[] blockLiterals = new Object[outerLiterals.length + 1];
        /*
         * FIXME: always using large frame for blocks (incorrect: code.needsLargeFrame ? 0x20000 : 0;)
         */
        blockLiterals[0] = makeHeader(numArgs, numCopied, code.numLiterals, false, true);
        for (int i = 1; i < outerLiterals.length; i++) {
            blockLiterals[i] = outerLiterals[i];
        }
        blockLiterals[blockLiterals.length - 1] = outerMethod; // last literal is back pointer to method
        this.literals = blockLiterals;
        this.bytes = Arrays.copyOfRange(code.getBytes(), bytecodeOffset, (bytecodeOffset + blockSize));
        decodeHeader();
    }

    private CompiledBlockObject(CompiledBlockObject original) {
        super(original);
        outerMethod = original.outerMethod;
        numCopiedValues = original.numCopiedValues;
        offset = original.offset;
    }

    @Override
    public Object at0(long longIndex) {
        int index = (int) longIndex;
        if (index < getBytecodeOffset() - getOffset()) {
            assert index % BYTES_PER_WORD == 0;
            return literals[index / BYTES_PER_WORD];
        } else {
            return getMethod().at0(longIndex);
        }
    }

    @Override
    public NativeObject getCompiledInSelector() {
        return outerMethod.getCompiledInSelector();
    }

    @Override
    public ClassObject getCompiledInClass() {
        return outerMethod.getCompiledInClass();
    }

    @Override
    public int getNumCopiedValues() {
        return numCopiedValues;
    }

    @Override
    public int getNumTemps() {
        return super.getNumTemps() + numCopiedValues;
    }

    @Override
    public CompiledMethodObject getMethod() {
        return outerMethod;
    }

    @Override
    public int getInitialPC() {
        return outerMethod.getInitialPC();
    }

    @Override
    public final int getOffset() {
        return offset;
    }

    @Override
    public BaseSqueakObject shallowCopy() {
        return new CompiledBlockObject(this);
    }
}
