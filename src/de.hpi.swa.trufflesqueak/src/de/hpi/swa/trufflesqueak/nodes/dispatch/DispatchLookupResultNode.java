/*
 * Copyright (c) 2017-2021 Software Architecture Group, Hasso Plattner Institute
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.dispatch;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import de.hpi.swa.trufflesqueak.model.ClassObject;
import de.hpi.swa.trufflesqueak.model.CompiledCodeObject;
import de.hpi.swa.trufflesqueak.model.NativeObject;
import de.hpi.swa.trufflesqueak.nodes.bytecodes.SendBytecodes.SelfSendNode;
import de.hpi.swa.trufflesqueak.nodes.dispatch.CreateFrameArgumentNodes.CreateFrameArgumentsForIndirectCallNode;

@ReportPolymorphism
@ImportStatic(SelfSendNode.class)
public abstract class DispatchLookupResultNode extends AbstractDispatchNode {
    public DispatchLookupResultNode(final NativeObject selector, final int argumentCount) {
        super(selector, argumentCount);
    }

    public static DispatchLookupResultNode create(final NativeObject selector, final int argumentCount) {
        return DispatchLookupResultNodeGen.create(selector, argumentCount);
    }

    public abstract Object execute(VirtualFrame frame, Object receiver, ClassObject receiverClass, Object lookupResult);

    @SuppressWarnings("unused")
    @Specialization(guards = "lookupResult == cachedLookupResult", limit = "INLINE_CACHE_SIZE", assumptions = {"dispatchNode.getCallTargetStable()"})
    protected static final Object doCached(final VirtualFrame frame, final Object receiver, final ClassObject receiverClass, final Object lookupResult,
                    @Cached("lookupResult") final Object cachedLookupResult,
                    @Cached("create(frame, selector, argumentCount, receiverClass, lookupResult)") final CachedDispatchNode dispatchNode) {
        return dispatchNode.execute(frame);
    }

    @Specialization(replaces = "doCached")
    protected final Object doIndirect(final VirtualFrame frame, final Object receiver, final ClassObject receiverClass, final Object lookupResult,
                    @Cached final ResolveMethodNode methodNode,
                    @Cached("create(frame, selector, argumentCount)") final CreateFrameArgumentsForIndirectCallNode argumentsNode,
                    @Cached final IndirectCallNode callNode) {
        final CompiledCodeObject method = methodNode.execute(getContext(), receiverClass, lookupResult);
        return callNode.call(method.getCallTarget(), argumentsNode.execute(frame, receiver, receiverClass, lookupResult, method));
    }
}
