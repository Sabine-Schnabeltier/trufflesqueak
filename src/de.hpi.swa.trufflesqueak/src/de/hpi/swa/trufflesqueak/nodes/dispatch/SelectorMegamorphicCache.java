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
import de.hpi.swa.trufflesqueak.model.CompiledCodeObject;
import de.hpi.swa.trufflesqueak.model.NativeObject;
import de.hpi.swa.trufflesqueak.nodes.AbstractNode;
import de.hpi.swa.trufflesqueak.nodes.dispatch.AbstractDisNode.LookupKind;
import de.hpi.swa.trufflesqueak.nodes.dispatch.AbstractDisNode.LookupResult;

public final class SelectorMegamorphicCache {
    private static final int INITIAL_CAPACITY = 8;

    private final NativeObject selector;

    private int size = 0;
    private int[] starts;
    private int[] ends;
    private LookupResult[] results;

    public SelectorMegamorphicCache(final NativeObject selector) {
        this.selector = selector;
        this.starts = new int[INITIAL_CAPACITY];
        this.ends = new int[INITIAL_CAPACITY];
        this.results = new LookupResult[INITIAL_CAPACITY];
    }

    public NativeObject getSelector() {
        return selector;
    }

    public LookupResult lookupCached(final AbstractNode contextNode, final ClassObject receiverClass) {
        final int id = receiverClass.getIntervalStart();

        final int[] currentStarts = starts;
        final int[] currentEnds = ends;
        final int currentSize = size;

        // TIER 1: Manually unrolled linear scan for small caches.
        // Because there are no loops, GraalVM perfectly unrolls this into
        // straight-line machine code without needing @CompilationFinal.
        if (currentSize <= 4) {
            if (currentSize > 0 && id >= currentStarts[0] && id <= currentEnds[0]) return results[0];
            if (currentSize > 1 && id >= currentStarts[1] && id <= currentEnds[1]) return results[1];
            if (currentSize > 2 && id >= currentStarts[2] && id <= currentEnds[2]) return results[2];
            if (currentSize > 3 && id >= currentStarts[3] && id <= currentEnds[3]) return results[3];
            return rebuildAndLookup(contextNode, receiverClass);
        }

        // TIER 2: Standard Binary Search for larger caches.
        int low = 0;
        int high = currentSize - 1;

        while (low <= high) {
            final int mid = (low + high) >>> 1;

            if (id < currentStarts[mid]) {
                high = mid - 1;
            } else if (id > currentEnds[mid]) {
                low = mid + 1;
            } else {
                return results[mid];
            }
        }

        return rebuildAndLookup(contextNode, receiverClass);
    }

    @TruffleBoundary
    private LookupResult rebuildAndLookup(final AbstractNode contextNode, final ClassObject receiverClass) {
        final LookupResult finalResult = AbstractDisNode.resolveTargetMethodByClass(contextNode, receiverClass, selector);

        // Exceptional cases (DNU and Object-As-Method) resolve using different selectors
        // under the hood. Upward chain-warming is mathematically unsafe for them.
        if (finalResult.kind() != LookupKind.STANDARD_METHOD) {
            final int currentId = receiverClass.getIntervalStart();
            if (currentId > 0) {
                insertOrMerge(currentId, finalResult);
            }
            return finalResult;
        }

        // Standard chain-warming for normal method lookups
        ClassObject current = receiverClass;
        while (current != null) {
            final int currentId = current.getIntervalStart();

            if (currentId > 0) {
                insertOrMerge(currentId, finalResult);
            }

            if (current.hasMethodDirectly(selector)) {
                break;
            }

            current = current.getSuperclassOrNull();
        }

        return finalResult;
    }

    private void insertOrMerge(final int id, final LookupResult newResult) {
        int low = 0;
        int high = size - 1;
        int insertIndex = 0;

        while (low <= high) {
            final int mid = (low + high) >>> 1;
            if (id < starts[mid]) {
                high = mid - 1;
                insertIndex = mid;
            } else if (id > ends[mid]) {
                low = mid + 1;
                insertIndex = mid + 1;
            } else {
                results[mid] = newResult;
                return;
            }
        }

        final CompiledCodeObject targetMethod = newResult.method();
        boolean mergedLeft = false;
        final int leftIndex = insertIndex - 1;

        if (leftIndex >= 0 && ends[leftIndex] == id - 1 && results[leftIndex].method() == targetMethod) {
            ends[leftIndex] = id;
            mergedLeft = true;
        }

        boolean mergedRight = false;
        final int rightIndex = insertIndex;

        if (rightIndex < size && starts[rightIndex] == id + 1 && results[rightIndex].method() == targetMethod) {
            if (mergedLeft) {
                ends[leftIndex] = ends[rightIndex];
                removeIntervalAt(rightIndex);
            } else {
                starts[rightIndex] = id;
            }
            mergedRight = true;
        }

        if (!mergedLeft && !mergedRight) {
            insertIntervalAt(insertIndex, id, id, newResult);
        }
    }

    private void removeIntervalAt(final int index) {
        final int elementsAfter = size - index - 1;
        if (elementsAfter > 0) {
            System.arraycopy(starts, index + 1, starts, index, elementsAfter);
            System.arraycopy(ends, index + 1, ends, index, elementsAfter);
            System.arraycopy(results, index + 1, results, index, elementsAfter);
        }

        size--;
        results[size] = null; // Prevent memory leak of the LookupResult
    }

    private void insertIntervalAt(final int index, final int start, final int end, final LookupResult result) {
        ensureCapacity();

        final int elementsAfter = size - index;
        if (elementsAfter > 0) {
            System.arraycopy(starts, index, starts, index + 1, elementsAfter);
            System.arraycopy(ends, index, ends, index + 1, elementsAfter);
            System.arraycopy(results, index, results, index + 1, elementsAfter);
        }

        starts[index] = start;
        ends[index] = end;
        results[index] = result;
        size++;
    }

    private void ensureCapacity() {
        if (size == starts.length) {
            final int newCapacity = starts.length * 2;
            starts = Arrays.copyOf(starts, newCapacity);
            ends = Arrays.copyOf(ends, newCapacity);
            results = Arrays.copyOf(results, newCapacity);
        }
    }

    public void flush() {
        // Clear object references to avoid memory leaks
        Arrays.fill(results, 0, size, null);
        size = 0;
    }

    public void flushForMethod(final CompiledCodeObject method) {
        flush();
    }
}