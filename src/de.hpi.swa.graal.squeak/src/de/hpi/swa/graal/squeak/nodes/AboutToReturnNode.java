package de.hpi.swa.graal.squeak.nodes;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.BranchProfile;

import de.hpi.swa.graal.squeak.exceptions.Returns.LocalReturn;
import de.hpi.swa.graal.squeak.exceptions.Returns.NonLocalReturn;
import de.hpi.swa.graal.squeak.exceptions.SqueakExceptions.SqueakException;
import de.hpi.swa.graal.squeak.model.BlockClosureObject;
import de.hpi.swa.graal.squeak.model.CompiledCodeObject;
import de.hpi.swa.graal.squeak.model.ContextObject;
import de.hpi.swa.graal.squeak.nodes.bytecodes.SendBytecodes.SendSelectorNode;
import de.hpi.swa.graal.squeak.nodes.context.TemporaryReadNode;
import de.hpi.swa.graal.squeak.nodes.context.TemporaryWriteNode;
import de.hpi.swa.graal.squeak.nodes.context.stack.StackPushNode;

public abstract class AboutToReturnNode extends AbstractNodeWithCode {
    protected AboutToReturnNode(final CompiledCodeObject code) {
        super(code);
    }

    public static AboutToReturnNode create(final CompiledCodeObject code) {
        return AboutToReturnNodeGen.create(code);
    }

    public abstract void executeAboutToReturn(VirtualFrame frame, NonLocalReturn nlr);

    /*
     * Virtualized version of Context>>aboutToReturn:through:, more specifically
     * Context>>resume:through:. This is only called if code.isUnwindMarked(), so there is no need
     * to unwind contexts here as this is already happening when NonLocalReturns are handled. Note
     * that this however does not check if the current context isDead nor does it terminate contexts
     * (this may be a problem).
     */
    @Specialization(guards = {"code.isUnwindMarked()", "isVirtualized(frame)", "isNil(frame, completeTempReadNode)"}, limit = "1")
    protected final void doAboutToReturnVirtualized(final VirtualFrame frame, @SuppressWarnings("unused") final NonLocalReturn nlr,
                    @Cached("create()") final GetBlockFrameArgumentsNode getFrameArguments,
                    @Cached("createTemporaryWriteNode(0)") final SqueakNode blockArgumentNode,
                    @SuppressWarnings("unused") @Cached("createTemporaryWriteNode(1)") final SqueakNode completeTempReadNode,
                    @Cached("create(code, 1)") final TemporaryWriteNode completeTempWriteNode,
                    @Cached("create()") final BlockActivationNode dispatchNode,
                    @Cached("create()") final BranchProfile nonLocalReturnProfile) {
        completeTempWriteNode.executeWrite(frame, code.image.sqTrue);
        final BlockClosureObject block = (BlockClosureObject) blockArgumentNode.executeRead(frame);
        try {
            dispatchNode.executeBlock(block, getFrameArguments.execute(block, getContextOrMarker(frame), new Object[0]));
        } catch (LocalReturn blockLR) { // ignore
        } catch (NonLocalReturn blockNLR) {
            nonLocalReturnProfile.enter();
            if (!blockNLR.hasArrivedAtTargetContext()) {
                throw blockNLR;
            }
        }
    }

    @SuppressWarnings("unused")
    @Specialization(guards = {"code.isUnwindMarked()", "isVirtualized(frame)", "!isNil(frame, completeTempReadNode)"}, limit = "1")
    protected final void doAboutToReturnVirtualizedNothing(final VirtualFrame frame, final NonLocalReturn nlr,
                    @Cached("createTemporaryWriteNode(1)") final SqueakNode completeTempReadNode) {
        // Nothing to do.
    }

    @Specialization(guards = {"code.isUnwindMarked()", "!isVirtualized(frame)"})
    protected final void doAboutToReturn(final VirtualFrame frame, final NonLocalReturn nlr,
                    @Cached("create(code)") final StackPushNode pushNode,
                    @Cached("createAboutToReturnSend()") final SendSelectorNode sendAboutToReturnNode) {
        final ContextObject context = getContext(frame);
        pushNode.executeWrite(frame, nlr.getTargetContext());
        pushNode.executeWrite(frame, nlr.getReturnValue());
        pushNode.executeWrite(frame, context);
        sendAboutToReturnNode.executeSend(frame);
    }

    @SuppressWarnings("unused")
    @Specialization(guards = {"!code.isUnwindMarked()"})
    protected final void doNothing(final VirtualFrame frame, final NonLocalReturn nlr) {
        // Nothing to do.
    }

    @Fallback
    protected static final void doFail(final NonLocalReturn nlr) {
        throw new SqueakException("Should never happend:", nlr);
    }

    protected final boolean isNil(final VirtualFrame frame, final SqueakNode completeTempReadNode) {
        return completeTempReadNode.executeRead(frame) == code.image.nil;
    }

    protected final SqueakNode createTemporaryWriteNode(final int tempIndex) {
        return TemporaryReadNode.create(code, tempIndex);
    }

    protected final SendSelectorNode createAboutToReturnSend() {
        return new SendSelectorNode(code, -1, -1, code.image.aboutToReturnSelector, 2);
    }
}
