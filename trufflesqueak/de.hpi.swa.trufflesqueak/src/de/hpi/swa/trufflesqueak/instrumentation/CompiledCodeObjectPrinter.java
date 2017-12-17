package de.hpi.swa.trufflesqueak.instrumentation;

import de.hpi.swa.trufflesqueak.model.CompiledCodeObject;
import de.hpi.swa.trufflesqueak.nodes.bytecodes.AbstractBytecodeNode;
import de.hpi.swa.trufflesqueak.nodes.bytecodes.push.PushClosureNode;
import de.hpi.swa.trufflesqueak.nodes.bytecodes.returns.ReturnTopFromBlockNode;
import de.hpi.swa.trufflesqueak.util.SqueakBytecodeDecoder;

public class CompiledCodeObjectPrinter {

    public static String getString(CompiledCodeObject code) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        int indent = 0;
        byte[] bytes = code.getBytes();
        // TODO: is a new BytecodeSequenceNode needed here?
        AbstractBytecodeNode[] bytecodeNodes = new SqueakBytecodeDecoder(code).decode();
        for (int i = 0; i < bytecodeNodes.length; i++) {
            AbstractBytecodeNode node = bytecodeNodes[i];
            if (node == null) {
                continue;
            }
            sb.append(index + " ");
            for (int j = 0; j < indent; j++) {
                sb.append(" ");
            }
            int numBytecodes = node.getNumBytecodes();
            sb.append("<");
            for (int j = i; j < i + numBytecodes; j++) {
                if (j > i) {
                    sb.append(" ");
                }
                if (j < bytes.length) {
                    sb.append(String.format("%02X", bytes[j]));
                }
            }
            sb.append("> ");
            sb.append(node.toString());
            if (i < bytecodeNodes.length - 1) {
                sb.append("\n");
            }
            if (node instanceof PushClosureNode) {
                indent++;
            } else if (node instanceof ReturnTopFromBlockNode) {
                indent--;
            }
            index++;
        }
        return sb.toString();
    }
}
