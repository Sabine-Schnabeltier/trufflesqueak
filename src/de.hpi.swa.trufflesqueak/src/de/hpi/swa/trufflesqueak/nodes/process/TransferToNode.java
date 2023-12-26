/*
 * Copyright (c) 2023 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2023 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.process;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

import de.hpi.swa.trufflesqueak.exceptions.ProcessSwitch;
import de.hpi.swa.trufflesqueak.exceptions.SqueakExceptions.SqueakException;
import de.hpi.swa.trufflesqueak.model.ContextObject;
import de.hpi.swa.trufflesqueak.model.PointersObject;
import de.hpi.swa.trufflesqueak.model.layout.ObjectLayouts.PROCESS;
import de.hpi.swa.trufflesqueak.model.layout.ObjectLayouts.PROCESS_SCHEDULER;
import de.hpi.swa.trufflesqueak.nodes.AbstractNode;
import de.hpi.swa.trufflesqueak.nodes.accessing.AbstractPointersObjectNodes.AbstractPointersObjectReadNode;
import de.hpi.swa.trufflesqueak.nodes.accessing.AbstractPointersObjectNodes.AbstractPointersObjectWriteNode;
import de.hpi.swa.trufflesqueak.nodes.context.frame.GetOrCreateContextNode;

@GenerateInline
@GenerateCached(false)
public abstract class TransferToNode extends AbstractNode {

    public abstract ProcessSwitch execute(VirtualFrame frame, Node node, PointersObject newProcess);

    public static final ProcessSwitch executeUncached(final VirtualFrame frame, final PointersObject newProcess) {
        // Record a process to be awakened on the next interpreter cycle.
        final PointersObject scheduler = getContext(null).getScheduler();
        final AbstractPointersObjectReadNode readNode = AbstractPointersObjectReadNode.getUncached();
        final AbstractPointersObjectWriteNode writeNode = AbstractPointersObjectWriteNode.getUncached();
        final PointersObject oldProcess = readNode.executePointers(null, scheduler, PROCESS_SCHEDULER.ACTIVE_PROCESS);
        writeNode.execute(null, scheduler, PROCESS_SCHEDULER.ACTIVE_PROCESS, newProcess);
        final ContextObject activeContext = GetOrCreateContextNode.getOrCreateUncached(frame);
        writeNode.execute(null, oldProcess, PROCESS.SUSPENDED_CONTEXT, activeContext);
        final Object newActiveContext = readNode.execute(null, newProcess, PROCESS.SUSPENDED_CONTEXT);
        writeNode.executeNil(null, newProcess, PROCESS.SUSPENDED_CONTEXT);
        writeNode.executeNil(null, newProcess, PROCESS.LIST);
        if (!(newActiveContext instanceof final ContextObject newActiveContextObject)) {
            throw SqueakException.create("new process not runnable");
        }
        throw ProcessSwitch.createWithBoundary(newActiveContextObject);
    }

    @Specialization
    protected static final ProcessSwitch transferTo(final VirtualFrame frame, final Node node, final PointersObject newProcess,
                    @Cached final GetOrCreateContextNode contextNode,
                    @Cached final AbstractPointersObjectReadNode readOldProcessNode,
                    @Cached final AbstractPointersObjectReadNode readNewActiveContextNode,
                    @Cached final AbstractPointersObjectWriteNode writeActiveProcessNode,
                    @Cached final AbstractPointersObjectWriteNode writeSuspendedContextNode,
                    @Cached final AbstractPointersObjectWriteNode writeNilContextNode,
                    @Cached final AbstractPointersObjectWriteNode writeListNode) {
        // Record a process to be awakened on the next interpreter cycle.
        final PointersObject scheduler = getContext(node).getScheduler();
        final PointersObject oldProcess = readOldProcessNode.executePointers(node, scheduler, PROCESS_SCHEDULER.ACTIVE_PROCESS);
        writeActiveProcessNode.execute(node, scheduler, PROCESS_SCHEDULER.ACTIVE_PROCESS, newProcess);
        writeSuspendedContextNode.execute(node, oldProcess, PROCESS.SUSPENDED_CONTEXT, contextNode.executeGet(frame, node));
        final Object newActiveContext = readNewActiveContextNode.execute(node, newProcess, PROCESS.SUSPENDED_CONTEXT);
        writeNilContextNode.executeNil(node, newProcess, PROCESS.SUSPENDED_CONTEXT);
        writeListNode.executeNil(node, newProcess, PROCESS.LIST);
        if (!(newActiveContext instanceof final ContextObject newActiveContextObject)) {
            throw SqueakException.create("new process not runnable");
        }
        if (CompilerDirectives.isPartialEvaluationConstant(newActiveContextObject)) {
            throw ProcessSwitch.create(newActiveContextObject);
        } else {
            // Avoid further PE if newActiveContext is not a PE constant.
            throw ProcessSwitch.createWithBoundary(newActiveContextObject);
        }
    }
}
