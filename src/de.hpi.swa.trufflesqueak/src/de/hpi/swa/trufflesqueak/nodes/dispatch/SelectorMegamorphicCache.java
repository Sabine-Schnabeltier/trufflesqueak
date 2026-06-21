/*
 * Copyright (c) 2026 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2026 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.dispatch;

import java.util.Arrays;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import de.hpi.swa.trufflesqueak.model.ClassObject;
import de.hpi.swa.trufflesqueak.model.NativeObject;
import de.hpi.swa.trufflesqueak.nodes.AbstractNode;
import de.hpi.swa.trufflesqueak.nodes.dispatch.AbstractDisNode.LookupKind;
import de.hpi.swa.trufflesqueak.nodes.dispatch.AbstractDisNode.LookupResult;

public final class SelectorMegamorphicCache {
    private static final int INITIAL_CAPACITY = 8;

    private final NativeObject selector;

    private int size = 0;
    private int[] boundaries;
    private LookupResult[] results;

    public SelectorMegamorphicCache(final NativeObject selector) {
        this.selector = selector;
        this.boundaries = new int[INITIAL_CAPACITY];
        this.results = new LookupResult[INITIAL_CAPACITY];
    }

    public NativeObject getSelector() {
        return selector;
    }

    public int getSize() {
        return size;
    }

    public int getCapacity() {
        return boundaries.length;
    }

    public int getValidResultsCount() {
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (results[i] != null) {
                count++;
            }
        }
        return count;
    }

    public int getCoveredIdSpan() {
        int span = 0;
        for (int i = 0; i < size; i++) {
            if (results[i] != null) {
                // Because the rightmost boundary always maps to null,
                // if we are here, (i + 1) is guaranteed to be within bounds.
                span += (boundaries[i + 1] - boundaries[i]);
            }
        }
        return span;
    }

    /**
     * The Hot Path: O(1) Unrolled Step Function followed by O(log N) Floor Binary Search.
     */
    public LookupResult lookupCached(final AbstractNode contextNode, final ClassObject receiverClass) {
        final int id = receiverClass.getTopologicalID();

        final int[] bounds = boundaries;
        final LookupResult[] res = results;
        final int currentSize = size;

        // TIER 1: Unrolled Step Function Loop (Optimal L1 Cache & Instruction Pipeline)
        if (currentSize <= 4) {
            if (currentSize == 0 || id < bounds[0]) {
                return rebuildAndLookup(contextNode, receiverClass);
            }
            if (currentSize == 1) {
                return res[0] != null ? res[0] : rebuildAndLookup(contextNode, receiverClass);
            }
            if (currentSize == 2) {
                if (id < bounds[1]) return res[0] != null ? res[0] : rebuildAndLookup(contextNode, receiverClass);
                return res[1] != null ? res[1] : rebuildAndLookup(contextNode, receiverClass);
            }
            if (currentSize == 3) {
                if (id < bounds[1]) return res[0] != null ? res[0] : rebuildAndLookup(contextNode, receiverClass);
                if (id < bounds[2]) return res[1] != null ? res[1] : rebuildAndLookup(contextNode, receiverClass);
                return res[2] != null ? res[2] : rebuildAndLookup(contextNode, receiverClass);
            }
            if (currentSize == 4) {
                if (id < bounds[1]) return res[0] != null ? res[0] : rebuildAndLookup(contextNode, receiverClass);
                if (id < bounds[2]) return res[1] != null ? res[1] : rebuildAndLookup(contextNode, receiverClass);
                if (id < bounds[3]) return res[2] != null ? res[2] : rebuildAndLookup(contextNode, receiverClass);
                return res[3] != null ? res[3] : rebuildAndLookup(contextNode, receiverClass);
            }
        }

        // TIER 2: Floor Binary Search
        if (id < bounds[0]) {
            return rebuildAndLookup(contextNode, receiverClass);
        }

        int low = 0;
        int high = currentSize - 1;
        int match = 0;

        while (low <= high) {
            final int mid = (low + high) >>> 1;
            if (bounds[mid] <= id) {
                match = mid;     // Valid lower bound found
                low = mid + 1;   // Search higher for a tighter fit
            } else {
                high = mid - 1;  // Overshot, search lower
            }
        }

        final LookupResult result = res[match];
        if (result != null) {
            return result;
        }

        return rebuildAndLookup(contextNode, receiverClass);
    }

    @TruffleBoundary
    private LookupResult rebuildAndLookup(final AbstractNode contextNode, final ClassObject receiverClass) {
        final LookupResult finalResult = AbstractDisNode.resolveTargetMethodByClass(contextNode, receiverClass, selector);

        // Do not perform upward chain-warming for exceptional routes like DNU
        if (finalResult.kind() != LookupKind.STANDARD_METHOD) {
            final int currentId = receiverClass.getTopologicalID();
            if (currentId > 0) {
                setPoint(currentId, finalResult);
            }
            return finalResult;
        }

        // Standard upward chain-warming
        ClassObject current = receiverClass;
        while (current != null) {
            final int currentId = current.getTopologicalID();

            if (currentId > 0) {
                setPoint(currentId, finalResult);
            }

            if (current.hasMethodDirectly(selector)) {
                break;
            }

            current = current.getSuperclassOrNull();
        }

        return finalResult;
    }

    /**
     * Splits and merges step-function intervals to set the result at a specific ID.
     */
    private void setPoint(final int id, final LookupResult result) {
        final LookupResult valAtId = getVal(id);
        if (isSameResult(valAtId, result)) {
            return; // No change needed
        }

        // Preserve the value that should exist immediately after our insertion point
        final LookupResult valAfter = getVal(id + 1);

        // Force boundaries in reverse order so we don't clobber valAfter
        forceBoundary(id + 1, valAfter);
        forceBoundary(id, result);

        // Clean up any redundant boundaries created by the split
        cleanUp(floorSearch(id + 1));
        cleanUp(floorSearch(id));
    }

    private int floorSearch(final int id) {
        if (size == 0 || id < boundaries[0]) {
            return -1;
        }
        int low = 0;
        int high = size - 1;
        int match = 0;

        while (low <= high) {
            final int mid = (low + high) >>> 1;
            if (boundaries[mid] <= id) {
                match = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return match;
    }

    private LookupResult getVal(final int id) {
        final int idx = floorSearch(id);
        return idx >= 0 ? results[idx] : null;
    }

    private void forceBoundary(final int x, final LookupResult val) {
        final int idx = floorSearch(x);
        if (idx >= 0 && boundaries[idx] == x) {
            results[idx] = val; // Direct overwrite if boundary exactly matches
        } else {
            insertAt(idx + 1, x, val); // Split existing segment
        }
    }

    private void cleanUp(final int idx) {
        if (idx < 0 || idx >= size) {
            return;
        }
        if (idx > 0 && isSameResult(results[idx], results[idx - 1])) {
            removeAt(idx);
        } else if (idx == 0 && results[0] == null) {
            // Because values < boundaries[0] implicitly evaluate to null,
            // an explicit boundary mapped to null at index 0 is redundant.
            removeAt(0);
        }
    }

    private static boolean isSameResult(final LookupResult a, final LookupResult b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.method() == b.method() && a.kind() == b.kind() && a.targetObject() == b.targetObject();
    }

    private void insertAt(final int index, final int x, final LookupResult val) {
        ensureCapacity();
        final int elementsAfter = size - index;
        if (elementsAfter > 0) {
            System.arraycopy(boundaries, index, boundaries, index + 1, elementsAfter);
            System.arraycopy(results, index, results, index + 1, elementsAfter);
        }
        boundaries[index] = x;
        results[index] = val;
        size++;
    }

    private void removeAt(final int index) {
        final int elementsAfter = size - index - 1;
        if (elementsAfter > 0) {
            System.arraycopy(boundaries, index + 1, boundaries, index, elementsAfter);
            System.arraycopy(results, index + 1, results, index, elementsAfter);
        }
        size--;
        results[size] = null; // Prevent memory leak
    }

    private void ensureCapacity() {
        if (size == boundaries.length) {
            final int newCapacity = boundaries.length * 2;
            boundaries = Arrays.copyOf(boundaries, newCapacity);
            results = Arrays.copyOf(results, newCapacity);
        }
    }

    public void flush() {
        boundaries = new int[INITIAL_CAPACITY];
        results = new LookupResult[INITIAL_CAPACITY];
        size = 0;
    }

    public void flushForMethod(final de.hpi.swa.trufflesqueak.model.CompiledCodeObject method) {
        flush();
    }
}
