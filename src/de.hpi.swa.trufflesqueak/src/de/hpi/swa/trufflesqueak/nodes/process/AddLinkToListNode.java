/*
 * Copyright (c) 2017-2025 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2021-2025 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.process;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

import de.hpi.swa.trufflesqueak.model.PointersObject;
import de.hpi.swa.trufflesqueak.model.layout.ObjectLayouts.LINKED_LIST;
import de.hpi.swa.trufflesqueak.model.layout.ObjectLayouts.PROCESS;
import de.hpi.swa.trufflesqueak.nodes.AbstractNode;
import de.hpi.swa.trufflesqueak.nodes.accessing.AbstractPointersObjectNodes.AbstractPointersObjectReadNode;
import de.hpi.swa.trufflesqueak.nodes.accessing.AbstractPointersObjectNodes.AbstractPointersObjectWriteNode;

/*
 * Add the given process to the given end of the given linked list.
 */
@GenerateInline
@GenerateCached(false)
public abstract class AddLinkToListNode extends AbstractNode {

    public static void executeUncached(final PointersObject process, final PointersObject list, final boolean addLast) {
        final AbstractPointersObjectReadNode readNode = AbstractPointersObjectReadNode.getUncached();
        final AbstractPointersObjectWriteNode writeNode = AbstractPointersObjectWriteNode.getUncached();
        if (addLast) {
            addLastLinkToList(null, process, list, addLast, readNode, writeNode);
        } else {
            addFirstLinkToList(null, process, list, addLast, readNode, writeNode);
        }
    }

    public abstract void execute(Node node, PointersObject process, PointersObject list, final boolean addLast);

    /**
     * Adding as the firstLink versus the lastLink differ in two ways.
     *
     * 1. LAST_LINK and FIRST_LINK are interchanged
     * 2. process.nextLink = firstLink versus lastLink.nextLink = process
     */
    @Specialization(guards = "!addLast")
    protected static final void addFirstLinkToList(final Node node, final PointersObject process, final PointersObject list, @SuppressWarnings("unused") final boolean addLast,
                    @Shared("read") @Cached final AbstractPointersObjectReadNode readNode,
                    @Shared("write") @Cached final AbstractPointersObjectWriteNode writeNode) {
        if (list.isEmptyList(readNode, node)) {
            writeNode.execute(node, list, LINKED_LIST.LAST_LINK, process);
        } else {
            final PointersObject firstLink = readNode.executePointers(node, list, LINKED_LIST.FIRST_LINK);
            writeNode.execute(node, process, PROCESS.NEXT_LINK, firstLink);
        }
        writeNode.execute(node, list, LINKED_LIST.FIRST_LINK, process);
        writeNode.execute(node, process, PROCESS.LIST, list);
    }

    @Specialization(guards = "addLast")
    protected static final void addLastLinkToList(final Node node, final PointersObject process, final PointersObject list, @SuppressWarnings("unused") final boolean addLast,
                    @Shared("read") @Cached final AbstractPointersObjectReadNode readNode,
                    @Shared("write") @Cached final AbstractPointersObjectWriteNode writeNode) {
        if (list.isEmptyList(readNode, node)) {
            writeNode.execute(node, list, LINKED_LIST.FIRST_LINK, process);
        } else {
            final PointersObject lastLink = readNode.executePointers(node, list, LINKED_LIST.LAST_LINK);
            writeNode.execute(node, lastLink, PROCESS.NEXT_LINK, process);
        }
        writeNode.execute(node, list, LINKED_LIST.LAST_LINK, process);
        writeNode.execute(node, process, PROCESS.LIST, list);
    }
}
