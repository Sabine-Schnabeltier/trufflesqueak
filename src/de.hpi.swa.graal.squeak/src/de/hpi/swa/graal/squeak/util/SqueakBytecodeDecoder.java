package de.hpi.swa.graal.squeak.util;

import java.util.ArrayList;
import java.util.List;

import de.hpi.swa.graal.squeak.exceptions.SqueakExceptions.SqueakException;
import de.hpi.swa.graal.squeak.model.CompiledBlockObject;
import de.hpi.swa.graal.squeak.model.CompiledCodeObject;
import de.hpi.swa.graal.squeak.model.CompiledMethodObject;
import de.hpi.swa.graal.squeak.nodes.bytecodes.AbstractBytecodeNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.JumpBytecodes;
import de.hpi.swa.graal.squeak.nodes.bytecodes.JumpBytecodes.ConditionalJumpNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.JumpBytecodes.UnconditionalJumpNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.MiscellaneousBytecodes.CallPrimitiveNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.MiscellaneousBytecodes.DoubleExtendedDoAnythingNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.MiscellaneousBytecodes.DupNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.MiscellaneousBytecodes.ExtendedBytecodes;
import de.hpi.swa.graal.squeak.nodes.bytecodes.MiscellaneousBytecodes.PopNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.MiscellaneousBytecodes.UnknownBytecodeNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.PushBytecodes.PushActiveContextNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.PushBytecodes.PushClosureNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.PushBytecodes.PushConstantNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.PushBytecodes.PushLiteralConstantNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.PushBytecodes.PushLiteralVariableNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.PushBytecodes.PushNewArrayNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.PushBytecodes.PushReceiverNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.PushBytecodes.PushReceiverVariableNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.PushBytecodes.PushRemoteTempNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.PushBytecodes.PushTemporaryLocationNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.ReturnBytecodes.ReturnConstantNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.ReturnBytecodes.ReturnReceiverNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.ReturnBytecodes.ReturnTopFromBlockNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.ReturnBytecodes.ReturnTopFromMethodNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.SendBytecodes.SecondExtendedSendNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.SendBytecodes.SendLiteralSelectorNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.SendBytecodes.SendSelectorNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.SendBytecodes.SingleExtendedSendNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.SendBytecodes.SingleExtendedSuperNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.StoreBytecodes.PopIntoReceiverVariableNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.StoreBytecodes.PopIntoRemoteTempNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.StoreBytecodes.PopIntoTemporaryLocationNode;
import de.hpi.swa.graal.squeak.nodes.bytecodes.StoreBytecodes.StoreIntoRemoteTempNode;

public final class SqueakBytecodeDecoder {
    private SqueakBytecodeDecoder() {
    }

    public static AbstractBytecodeNode[] decode(final CompiledCodeObject code) {
        final int trailerPosition = trailerPosition(code);
        final AbstractBytecodeNode[] nodes = new AbstractBytecodeNode[trailerPosition];
        int index = 0;
        int lineNumber = 1;
        while (index < trailerPosition) {
            final AbstractBytecodeNode bytecodeNode = decodeBytecode(code, index);
            bytecodeNode.setLineNumber(lineNumber);
            nodes[index] = bytecodeNode;
            index = bytecodeNode.getSuccessorIndex();
            lineNumber++;
        }
        return nodes;
    }

    public static int trailerPosition(final CompiledCodeObject code) {
        return code instanceof CompiledBlockObject ? code.getBytes().length : trailerPosition(code.getBytes());
    }

    private static int trailerPosition(final byte[] bytecode) {
        final int bytecodeLength = bytecode.length;
        final int flagByte = Byte.toUnsignedInt(bytecode[bytecodeLength - 1]);
        final int index = (flagByte >> 2) + 1;
        switch (index) {
            case 1: // #decodeNoTrailer
                return bytecodeLength - 1;
            case 2: // #decodeClearedTrailer
                return decodeLengthField(bytecode, bytecodeLength, flagByte);
            case 3: // #decodeTempsNamesQCompress
                return decodeLengthField(bytecode, bytecodeLength, flagByte);
            case 4: // #decodeTempsNamesZip
                return decodeLengthField(bytecode, bytecodeLength, flagByte);
            case 5: // #decodeSourceBySelector
                return bytecodeLength - 1;
            case 6: // #decodeSourceByStringIdentifier
                return decodeLengthField(bytecode, bytecodeLength, flagByte);
            case 7: // #decodeEmbeddedSourceQCompress
                return decodeLengthField(bytecode, bytecodeLength, flagByte);
            case 8: // #decodeEmbeddedSourceZip
                return decodeLengthField(bytecode, bytecodeLength, flagByte);
            case 9: // decodeVarLengthSourcePointer
                int pos = bytecodeLength - 2;
                while (bytecode[pos] < 0) {
                    pos--;
                }
                return pos;
            case 64: // decodeSourcePointer
                return bytecodeLength - 4;
            default:
                throw SqueakException.create("Undefined method encoding (see CompiledMethodTrailer).");
        }
    }

    private static int decodeLengthField(final byte[] bytecode, final int bytecodeLength, final int flagByte) {
        final int numBytes = (flagByte & 3) + 1;
        int length = 0;
        final int firstLengthValueIndex = bytecodeLength - 2;
        for (int i = 0; i < numBytes; i++) {
            length = (length << 8) + Byte.toUnsignedInt(bytecode[firstLengthValueIndex - i]);
        }
        return bytecodeLength - (1 + numBytes + length);
    }

    public static AbstractBytecodeNode decodeBytecode(final CompiledCodeObject code, final int index) {
        final byte[] bytecode = code.getBytes();
        final int b = Byte.toUnsignedInt(bytecode[index]);
        //@formatter:off
        switch (b) {
            case 0: case 1: case 2: case 3: case 4: case 5: case 6: case 7:
            case 8: case 9: case 10: case 11: case 12: case 13: case 14: case 15:
                return PushReceiverVariableNode.create(code, index, 1, b & 15);
            case 16: case 17: case 18: case 19: case 20: case 21: case 22: case 23:
            case 24: case 25: case 26: case 27: case 28: case 29: case 30: case 31:
                return new PushTemporaryLocationNode(code, index, 1, b & 15);
            case 32: case 33: case 34: case 35: case 36: case 37: case 38: case 39:
            case 40: case 41: case 42: case 43: case 44: case 45: case 46: case 47:
            case 48: case 49: case 50: case 51: case 52: case 53: case 54: case 55:
            case 56: case 57: case 58: case 59: case 60: case 61: case 62: case 63:
                return new PushLiteralConstantNode(code, index, 1, b & 31);
            case 64: case 65: case 66: case 67: case 68: case 69: case 70: case 71:
            case 72: case 73: case 74: case 75: case 76: case 77: case 78: case 79:
            case 80: case 81: case 82: case 83: case 84: case 85: case 86: case 87:
            case 88: case 89: case 90: case 91: case 92: case 93: case 94: case 95:
                return new PushLiteralVariableNode(code, index, 1, b & 31);
            case 96: case 97: case 98: case 99: case 100: case 101: case 102: case 103:
                return new PopIntoReceiverVariableNode(code, index, 1, b & 7);
            case 104: case 105: case 106: case 107: case 108: case 109: case 110: case 111:
                return new PopIntoTemporaryLocationNode(code, index, 1, b & 7);
            case 112:
                return PushReceiverNode.create(code, index);
            case 113:
                return new PushConstantNode(code, index, true);
            case 114:
                return new PushConstantNode(code, index, false);
            case 115:
                return new PushConstantNode(code, index, code.image.nil);
            case 116:
                return new PushConstantNode(code, index, -1L);
            case 117:
                return new PushConstantNode(code, index, 0L);
            case 118:
                return new PushConstantNode(code, index, 1L);
            case 119:
                return new PushConstantNode(code, index, 2L);
            case 120:
                return ReturnReceiverNode.create(code, index);
            case 121:
                return ReturnConstantNode.create(code, index, true);
            case 122:
                return ReturnConstantNode.create(code, index, false);
            case 123:
                return ReturnConstantNode.create(code, index, code.image.nil);
            case 124:
                return ReturnTopFromMethodNode.create(code, index);
            case 125:
                return ReturnTopFromBlockNode.create(code, index);
            case 126:
                return new UnknownBytecodeNode(code, index, 1, b);
            case 127:
                return new UnknownBytecodeNode(code, index, 1, b);
            case 128:
                return ExtendedBytecodes.createPush(code, index, 2, Byte.toUnsignedInt(bytecode[index + 1]));
            case 129:
                return ExtendedBytecodes.createStoreInto(code, index, 2, Byte.toUnsignedInt(bytecode[index + 1]));
            case 130:
                return ExtendedBytecodes.createPopInto(code, index, 2, Byte.toUnsignedInt(bytecode[index + 1]));
            case 131:
                return new SingleExtendedSendNode(code, index, 2, Byte.toUnsignedInt(bytecode[index + 1]));
            case 132:
                return DoubleExtendedDoAnythingNode.create(code, index, 3, Byte.toUnsignedInt(bytecode[index + 1]), Byte.toUnsignedInt(bytecode[index + 2]));
            case 133:
                return new SingleExtendedSuperNode(code, index, 2, Byte.toUnsignedInt(bytecode[index + 1]));
            case 134:
                return new SecondExtendedSendNode(code, index, 2, Byte.toUnsignedInt(bytecode[index + 1]));
            case 135:
                return new PopNode(code, index, 1);
            case 136:
                return new DupNode(code, index, 1);
            case 137:
                return new PushActiveContextNode(code, index);
            case 138:
                return PushNewArrayNode.create(code, index, 2, Byte.toUnsignedInt(bytecode[index + 1]));
            case 139:
                assert code instanceof CompiledMethodObject;
                return CallPrimitiveNode.create((CompiledMethodObject) code, index, Byte.toUnsignedInt(bytecode[index + 1]), Byte.toUnsignedInt(bytecode[index + 2]));
            case 140:
                return new PushRemoteTempNode(code, index, 3, Byte.toUnsignedInt(bytecode[index + 1]), Byte.toUnsignedInt(bytecode[index + 2]));
            case 141:
                return new StoreIntoRemoteTempNode(code, index, 3, Byte.toUnsignedInt(bytecode[index + 1]), Byte.toUnsignedInt(bytecode[index + 2]));
            case 142:
                return new PopIntoRemoteTempNode(code, index, 3, Byte.toUnsignedInt(bytecode[index + 1]), Byte.toUnsignedInt(bytecode[index + 2]));
            case 143:
                return PushClosureNode.create(code, index, 4,
                                Byte.toUnsignedInt(bytecode[index + 1]), Byte.toUnsignedInt(bytecode[index + 2]), Byte.toUnsignedInt(bytecode[index + 3]));
            case 144: case 145: case 146: case 147: case 148: case 149: case 150: case 151:
                return new UnconditionalJumpNode(code, index, 1, b);
            case 152: case 153: case 154: case 155: case 156: case 157: case 158: case 159:
                return new ConditionalJumpNode(code, index, 1, b);
            case 160: case 161: case 162: case 163: case 164: case 165: case 166: case 167:
                return new UnconditionalJumpNode(code, index, 2, b, Byte.toUnsignedInt(bytecode[index + 1]));
            case 168: case 169: case 170: case 171:
                return new ConditionalJumpNode(code, index, 2, b, Byte.toUnsignedInt(bytecode[index + 1]), true);
            case 172: case 173: case 174: case 175:
                return new ConditionalJumpNode(code, index, 2, b, Byte.toUnsignedInt(bytecode[index + 1]), false);
            case 176: case 177: case 178: case 179: case 180: case 181: case 182: case 183:
            case 184: case 185: case 186: case 187: case 188: case 189: case 190: case 191:
            case 192: case 193: case 194: case 195: case 196: case 197: case 198: case 199:
            case 200: case 201: case 202: case 203: case 204: case 205: case 206: case 207:
                return SendSelectorNode.createForSpecialSelector(code, index, b - 176);
            case 208: case 209: case 210: case 211: case 212: case 213: case 214: case 215:
            case 216: case 217: case 218: case 219: case 220: case 221: case 222: case 223:
                return SendLiteralSelectorNode.create(code, index, 1, b & 0xF, 0);
            case 224: case 225: case 226: case 227: case 228: case 229: case 230: case 231:
            case 232: case 233: case 234: case 235: case 236: case 237: case 238: case 239:
                return SendLiteralSelectorNode.create(code, index, 1, b & 0xF, 1);
            case 240: case 241: case 242: case 243: case 244: case 245: case 246: case 247:
            case 248: case 249: case 250: case 251: case 252: case 253: case 254: case 255:
                return SendLiteralSelectorNode.create(code, index, 1, b & 0xF, 2);
            default:
                throw SqueakException.create("Unknown bytecode:", b);
        }
        //@formatter:on
    }

    public static int[][] findLoops(final CompiledCodeObject code) {
        final List<int[]> loopIndices = new ArrayList<>();
        final byte[] bytecode = code.getBytes();
        for (int index = 0; index < bytecode.length; index++) {
            final int currentPC = index;
            final int b = Byte.toUnsignedInt(bytecode[currentPC]);
            //@formatter:off
            final int jumpOffset;
            switch (b) {
                case 144: case 145: case 146: case 147: case 148: case 149: case 150: case 151:
                     jumpOffset = JumpBytecodes.shortJumpOffset(b);
                     break;
                case 152: case 153: case 154: case 155: case 156: case 157: case 158: case 159:
                     jumpOffset = JumpBytecodes.shortJumpOffset(b);
                     break;
                case 160: case 161: case 162: case 163: case 164: case 165: case 166: case 167:
                     jumpOffset = JumpBytecodes.longUnconditionalJumpOffset(b, Byte.toUnsignedInt(bytecode[++index]));
                     break;
                case 168: case 169: case 170: case 171: // true
                    jumpOffset = JumpBytecodes.longConditionalJumpOffset(b, Byte.toUnsignedInt(bytecode[++index]));
                    break;
                case 172: case 173: case 174: case 175: // false
                    jumpOffset = JumpBytecodes.longConditionalJumpOffset(b, Byte.toUnsignedInt(bytecode[++index]));
                    break;
                default:
                    continue;
            }
            //@formatter:on
            if (jumpOffset < 0) {
                loopIndices.add(new int[]{index + 1 + jumpOffset, index});
            }
        }
        return loopIndices.toArray(new int[loopIndices.size()][2]);
    }
}
