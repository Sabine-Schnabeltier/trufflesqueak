/*
 * Copyright (c) 2026 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2026 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.nodes.dispatch;

import java.util.Arrays;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ExplodeLoop;

import de.hpi.swa.trufflesqueak.model.ClassObject;
import de.hpi.swa.trufflesqueak.model.CompiledCodeObject;
import de.hpi.swa.trufflesqueak.model.NativeObject;
import de.hpi.swa.trufflesqueak.nodes.AbstractNode;
import de.hpi.swa.trufflesqueak.nodes.dispatch.AbstractDisNode.LookupResult;

import static de.hpi.swa.trufflesqueak.nodes.dispatch.AbstractDisNode.resolveTargetMethodByClass;

public final class SelectorMegamorphicCache {
    private static final int GOLDEN_RATIO_32 = 0x9E3779B9;

    private static final int ENTRY_STEP = 2; // [ClassObject, LookupResult]
    private static final int CACHE_WAYS = 4;
    private static final int BUCKET_STRIDE = CACHE_WAYS * ENTRY_STEP;

    private static final int EVICTION_COUNT_FOR_GROW = 16;

    private static final int INITIAL_MEGAMORPHIC_CACHE_SIZE = 4 * BUCKET_STRIDE;
    private static final int MAX_MEGAMORPHIC_CACHE_SIZE = 512;

    private final NativeObject selector;

    private Object[] cacheArray;
    private int cacheSize;
    private int evictions = 0;

    public SelectorMegamorphicCache(final NativeObject selector) {
        this.selector = selector;
        this.cacheSize = INITIAL_MEGAMORPHIC_CACHE_SIZE;
        this.cacheArray = new Object[INITIAL_MEGAMORPHIC_CACHE_SIZE];
    }

    public NativeObject getSelector() {
        return selector;
    }

    /**
     * High-speed, inlinable entry point for megamorphic dispatch.
     */
    public LookupResult lookupCached(final AbstractNode contextNode, final ClassObject receiverClass) {
        final Object[] currentCache = cacheArray;

        final int bucketBaseIndex = cacheIndexFor(receiverClass, currentCache.length);
        final int hitResultIndex = scanBucketForClass(currentCache, bucketBaseIndex, receiverClass);

        if (hitResultIndex != -1) {
            return (LookupResult) currentCache[hitResultIndex];
        }

        return lookupAndRecordCacheMiss(contextNode, currentCache, receiverClass, bucketBaseIndex);
    }

    @TruffleBoundary
    private LookupResult lookupAndRecordCacheMiss(final AbstractNode contextNode, final Object[] passedCacheArray, final ClassObject receiverClass, final int oldBucketBase) {
        final Object[] activeCache = cacheArray;

        if (passedCacheArray != activeCache) {
            return checkActiveCacheOrLookup(contextNode, activeCache, receiverClass);
        }

        if (evictions > EVICTION_COUNT_FOR_GROW && cacheSize < MAX_MEGAMORPHIC_CACHE_SIZE) {
            cacheSize *= 2;
            final Object[] newCache = new Object[cacheSize];

            for (int i = 0; i < activeCache.length; i += ENTRY_STEP) {
                final ClassObject oldClass = (ClassObject) activeCache[i];
                if (oldClass != null) {
                    final LookupResult oldResult = (LookupResult) activeCache[i + 1];
                    final int rehashBase = cacheIndexFor(oldClass, cacheSize);
                    writeToFirstAvailableOrEvict(newCache, rehashBase, oldClass, oldResult);
                }
            }

            final LookupResult result = resolveTargetMethodByClass(contextNode, receiverClass, selector);
            final int newBase = cacheIndexFor(receiverClass, cacheSize);
            writeToFirstAvailableOrEvict(newCache, newBase, receiverClass, result);
            writeToFirstAvailableOrEvict(activeCache, oldBucketBase, receiverClass, result);

            cacheArray = newCache;
            evictions = 0;
            return result;
        }

        final LookupResult result = resolveTargetMethodByClass(contextNode, receiverClass, selector);
        if (writeToFirstAvailableOrEvict(activeCache, oldBucketBase, receiverClass, result)) {
            evictions++;
        }
        return result;
    }

    private LookupResult checkActiveCacheOrLookup(final AbstractNode contextNode, final Object[] activeCache, final ClassObject receiverClass) {
        final int activeBase = cacheIndexFor(receiverClass, activeCache.length);
        final int hitIndex = scanBucketForClass(activeCache, activeBase, receiverClass);

        if (hitIndex != -1) {
            return (LookupResult) activeCache[hitIndex];
        }

        final LookupResult result = resolveTargetMethodByClass(contextNode, receiverClass, selector);
        if (writeToFirstAvailableOrEvict(activeCache, activeBase, receiverClass, result)) {
            evictions++;
        }
        return result;
    }

    private static boolean writeToFirstAvailableOrEvict(final Object[] cache, final int bucketBase, final ClassObject receiverClass, final LookupResult result) {
        for (int way = 0; way < CACHE_WAYS; way++) {
            final int targetIndex = bucketBase + (way * ENTRY_STEP);
            if (cache[targetIndex] == null) {
                cache[targetIndex] = receiverClass;
                cache[targetIndex + 1] = result;
                return false;
            }
        }

        final int chosenWay = (System.identityHashCode(receiverClass) & Integer.MAX_VALUE) % CACHE_WAYS;
        final int evictIdx = bucketBase + (chosenWay * ENTRY_STEP);
        cache[evictIdx] = receiverClass;
        cache[evictIdx + 1] = result;
        return true;
    }

    @ExplodeLoop
    private static int scanBucketForClass(final Object[] cache, final int bucketBaseIndex, final ClassObject receiverClass) {
        for (int way = 0; way < CACHE_WAYS; way++) {
            final int currentIndex = bucketBaseIndex + (way * ENTRY_STEP);
            if (cache[currentIndex] == receiverClass) {
                return currentIndex + 1; // Return the index of the Result
            }
        }
        return -1;
    }

    private static int cacheIndexFor(final ClassObject receiverClass, final int cacheLength) {
        final int mixed = System.identityHashCode(receiverClass) * GOLDEN_RATIO_32;
        final int totalBuckets = cacheLength / BUCKET_STRIDE;
        return (mixed & (totalBuckets - 1)) * BUCKET_STRIDE;
    }

    public void flush() {
        Arrays.fill(cacheArray, null);
        evictions = 0;
    }

    public void flushForMethod(final CompiledCodeObject method) {
        for (int i = 0; i < cacheArray.length; i += ENTRY_STEP) {
            final LookupResult result = (LookupResult) cacheArray[i + 1];
            if (result != null && result.method() == method) {
                cacheArray[i] = null;     // Clear the class
                cacheArray[i + 1] = null; // Clear the result
            }
        }
    }
}
