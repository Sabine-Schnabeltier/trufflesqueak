/*
 * Copyright (c) 2025 Software Architecture Group, Hasso Plattner Institute
 * Copyright (c) 2025 Oracle and/or its affiliates
 *
 * Licensed under the MIT License.
 */
package de.hpi.swa.trufflesqueak.util;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.frame.VirtualFrame;
import de.hpi.swa.trufflesqueak.model.AbstractSqueakObject;
import de.hpi.swa.trufflesqueak.model.CompiledCodeObject;
import de.hpi.swa.trufflesqueak.model.ContextObject;
import de.hpi.swa.trufflesqueak.model.FrameMarker;
import de.hpi.swa.trufflesqueak.model.NilObject;
import de.hpi.swa.trufflesqueak.nodes.AbstractNode;

public final class ContextUtils {

    static boolean debug;

    private ContextUtils() {
    }

    /**
     *  Find homeContext or the first unwind-marked context, whichever occurs first.
     *  Returns null if homeContext not found on sender chain. Avoids materialization.
     */
    public static ContextObject findStopContext(final ContextObject startContext, final ContextObject homeContext, final AbstractNode node) {
        ContextObject unwindContext = null;
        ContextObject currentContext = startContext;
        while (currentContext.hasMaterializedSender()) {
            final AbstractSqueakObject sender = currentContext.getSender();

            if (debug && sender != NilObject.SINGLETON)  dumpFrame(((ContextObject) sender).getTruffleFrame(), homeContext);

            if (sender == homeContext) {
                return homeContext;         /* found homeContext */
            } else if (sender == NilObject.SINGLETON) {
                return null;                /* homeContext not found */
            }

            currentContext = (ContextObject) sender;
            if (unwindContext == null && currentContext.isUnwindMarkedNonClosure()) {
                unwindContext = currentContext;
            }
        }

        /* continue following sender chain on the stack */
        final ContextObject stopContext = findStopContext((FrameMarker) currentContext.getFrameSender(), homeContext, node);

        if (stopContext == null)
            return null;            /* unable to find homeContext */
        else if (unwindContext != null)
            return unwindContext;   /* use the unwind context found here */
        else
            return stopContext;     /* use result from lower down on the stack */
    }

    /**
     *  Find homeContext or the first unwind-marked context, whichever occurs first.
     *  Returns null if homeContext not found on sender chain. Avoids materialization.
     */
    @TruffleBoundary
    public static ContextObject findStopContext(final FrameMarker frameMarker, final ContextObject homeContext, final AbstractNode node) {

        final ContextObject[] unwindContext = new ContextObject[1];
        final ContextObject[] bottomContextOnTruffleStack = new ContextObject[1];

        final ContextObject stopContext = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<>() {
            boolean foundMyself;
            ContextObject unwind = null;

            @Override
            public ContextObject visitFrame(final FrameInstance frameInstance) {
                final Frame currentFrame = frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY);
                if (!FrameAccess.isTruffleSqueakFrame(currentFrame)) {
                    return null;            /* Foreign frame cannot be unwind marked. */
                }

                if (!foundMyself) {
                    if (frameMarker == FrameAccess.getMarker(currentFrame)) {
                        foundMyself = true;
                    } else {
                        return null;        /* skip frames until we get to the desired start frame */
                    }
                }

                if (debug) dumpFrame(currentFrame, homeContext);

                final ContextObject context = FrameAccess.getContext(currentFrame);
                if (context == homeContext) {
                    return homeContext;     /* found homeContext */
                }

                /* save first found unwind marked context */
                if (unwind == null && FrameAccess.isUnwindMarkedNonClosure(currentFrame)) {
                    final ContextObject c;
                    if (context != null) {
                        c = context;
                    } else {
                        c = ContextObject.create(node.getContext(), frameInstance);
                    }
                    unwind = unwindContext[0] = c;
                }

                /* save the bottommost context -- last stack frame has materialized sender */
                if (context == null) {
                    if (FrameAccess.getSender(currentFrame) instanceof final ContextObject sender) {
                        if (sender == homeContext) {
                            return homeContext;
                        }
                        bottomContextOnTruffleStack[0] = sender;
                    }
                } else {
                    bottomContextOnTruffleStack[0] = context;

                    if (context.hasModifiedSender()) {
                        return context;     /* frame stack may not be the same as sender chain */
                    }
                }
                return null;
            }
        });

        // At this point, stopContext is either homeContext, a modified-sender context or null.
        // unwindContext[0] holds the first (if any) marked context

        /* found homeContext, exit with first unwind context or homeContext */
        if (stopContext == homeContext) {
            return unwindContext[0] == null ? homeContext : unwindContext[0];
        }

        /* continue following sender chain via contexts */
        final ContextObject nextStopContext;
        if (stopContext == null) {
            final ContextObject startContext = bottomContextOnTruffleStack[0];
            if (startContext == null) {
                throw new RuntimeException("Could not find terminal Context in sender chain");
            } else {
                nextStopContext = findStopContext(startContext, homeContext, node);
            }
        } else {
            nextStopContext = findStopContext(stopContext, homeContext, node);
        }

        if (nextStopContext == null)
            return null;                /* unable to find homeContext */
        else if (unwindContext[0] != null)
            return unwindContext[0];    /* use the unwind context found here */
        else
            return nextStopContext;     /* use result from lower down on the stack */
    }

    /**
     *  Find homeContext or the first unwind-marked context, whichever occurs first.
     *  Returns null if homeContext not found on sender chain. Avoids materialization.
     */
    public static ContextObject findStopContext(final VirtualFrame frame, final ContextObject homeContext, final AbstractNode node) {
        final ContextObject stopContext;

        if (debug) {
            System.out.print("Return to: ");
            System.out.println(homeContext);
            System.out.println(">>>>>>>>");
        }

        if (FrameAccess.hasContext(frame) && FrameAccess.hasMaterializedSender(frame)) {
            stopContext = findStopContext(FrameAccess.getContext(frame), homeContext, node);
        } else {
            stopContext = findStopContext(FrameAccess.getMarker(frame), homeContext, node);
        }

        if (debug) {
            System.out.println(stopContext);
            System.out.println("<<<<<<<<");
        }

        return stopContext;
    }

    /**
     *  Returns true if the sender chain starting at startContext includes endContext.
     */
    public static boolean hasSenderChainFromTo(final ContextObject startContext, final ContextObject endContext) {
        ContextObject currentContext = startContext;
        while (currentContext.hasMaterializedSender()) {
            final AbstractSqueakObject sender = currentContext.getSender();
            if (sender == endContext) {
                return true;
            } else if (sender == NilObject.SINGLETON) {
                return false;
            } else {
                currentContext = (ContextObject) sender;
            }
        }
        /* continue following sender chain on the stack */
        return hasSenderChainFromTo((FrameMarker) currentContext.getFrameSender(), endContext);
    }

    @TruffleBoundary
    private static boolean hasSenderChainFromTo(final FrameMarker startFrame, final ContextObject endContext) {
        assert startFrame != null : "Unexpected `null` value";
        final ContextObject[] bottomContextOnTruffleStack = new ContextObject[1];

        final ContextObject result = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<>() {
            boolean foundMyself;

            @Override
            public ContextObject visitFrame(final FrameInstance frameInstance) {
                final Frame currentFrame = frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY);
                if (!FrameAccess.isTruffleSqueakFrame(currentFrame)) {
                    return null;
                }

                if (!foundMyself) {
                    if (startFrame == FrameAccess.getMarker(currentFrame)) {
                        foundMyself = true;
                    } else {
                        return null;        /* skip frames until we get to the desired start frame */
                    }
                }

                final ContextObject context = FrameAccess.getContext(currentFrame);
                if (context == endContext) {
                    return endContext;
                }

                /* save the bottommost context -- last frame has materialized sender */
                if (context == null) {
                    if (FrameAccess.getSender(currentFrame) instanceof final ContextObject sender) {
                        if (sender == endContext) {
                            return endContext;
                        }
                        bottomContextOnTruffleStack[0] = sender;
                    }
                } else {
                    bottomContextOnTruffleStack[0] = context;

                    if (context.hasModifiedSender()) {
                        return context;     /* frame stack may not be the same as sender chain */
                    }
                }
                return null;
            }
        });

        // At this point, result is either endContext, a modified-sender context or null.

        /* found endContext */
        if (result == endContext) {
            return true;
        }

        /* modified-sender context */
        if (result != null) {
            return hasSenderChainFromToRecursively(result, endContext);
        }

        /* continue until reaching endContext */
        if (bottomContextOnTruffleStack[0] != null) {
            return hasSenderChainFromToRecursively(bottomContextOnTruffleStack[0], endContext);
        }

        /* never encountered endContext */
        return false;
    }

    @TruffleBoundary
    private static boolean hasSenderChainFromToRecursively(final ContextObject startContext, final ContextObject endContext) {
        return hasSenderChainFromTo(startContext, endContext);
    }

    /**
     *  Terminate all of the contexts between startContext and endContext.
     */
    public static void terminateBetween(final ContextObject startContext, final ContextObject endContext) {
        ContextObject currentContext = startContext;
        while (currentContext.hasMaterializedSender()) {
            final AbstractSqueakObject sender = currentContext.getSender();
            if (currentContext != startContext) {
                System.out.println(currentContext);
                currentContext.terminate();
            }
            if (sender == endContext || sender == NilObject.SINGLETON) {
                return;
            } else {
                currentContext = (ContextObject) sender;
            }
        }
        /* continue following sender chain on the stack */
        terminateBetween((FrameMarker) currentContext.getFrameSender(), endContext);
    }

    @TruffleBoundary
    private static void terminateBetween(final FrameMarker intermediateFrame, final ContextObject endContext) {
        assert intermediateFrame != null : "Unexpected `null` value";
        final ContextObject[] bottomContextOnTruffleStack = new ContextObject[1];

        final ContextObject result = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<>() {
            boolean foundMyself;

            @Override
            public ContextObject visitFrame(final FrameInstance frameInstance) {
                final Frame currentFrame = frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY);
                if (!FrameAccess.isTruffleSqueakFrame(currentFrame)) {
                    return null;
                }

                if (!foundMyself) {
                    if (intermediateFrame == FrameAccess.getMarker(currentFrame)) {
                        foundMyself = true;
                    } else {
                        return null;        /* skip frames until we get to the desired frame */
                    }
                }

                final ContextObject context = FrameAccess.getContext(currentFrame);
                if (context == endContext) {
                    return endContext;
                }

                if (context != null && context.hasModifiedSender()) {
                    return context;     /* frame stack may not be the same as sender chain */
                }

                /* save the bottommost context -- last frame has materialized sender */
                if (FrameAccess.getSender(currentFrame) instanceof final ContextObject sender) {

                    if (sender == endContext) {
                        return endContext;
                    }
                    bottomContextOnTruffleStack[0] = sender;
                } else {
                    bottomContextOnTruffleStack[0] = null;
                }

                /* Terminate frame */
                final Frame currentWritable = frameInstance.getFrame(FrameInstance.FrameAccess.READ_WRITE);
                ContextUtils.dumpFrame(currentWritable,null);
                FrameAccess.terminate(currentWritable);

                return null;
            }
        });

        // At this point, result is either endContext, a modified-sender context or null.

        /* found endContext */
        if (result == endContext) {
            return;
        }
        System.out.print("result: ");
        System.out.println(result);

        /* modified-sender context */
        if (result != null) {
            terminateBetweenRecursively(result, endContext);
            return;
        }

        System.out.print("bottom: ");
        System.out.println(bottomContextOnTruffleStack[0]);

        /* continue until reaching endContext */
        if (bottomContextOnTruffleStack[0] != null) {
            terminateBetweenRecursively(bottomContextOnTruffleStack[0], endContext);
        }
    }

    @TruffleBoundary
    private static void terminateBetweenRecursively(final ContextObject intermediateContext, final ContextObject endContext) {
        terminateBetween(intermediateContext, endContext);
        System.out.println(intermediateContext);
        intermediateContext.terminate();
    }

    public static void dumpFrame (final Frame frame, ContextObject homeContext) {
        final ContextObject context = FrameAccess.getContext(frame);
        final CompiledCodeObject code = FrameAccess.getCodeObject(frame);
        final Object senderMarkerOrNil = FrameAccess.getSender(frame);
        final Object marker = FrameAccess.getMarker(frame);
        final String prefix = FrameAccess.hasClosure(frame) ? "[] in " : "";
        final String escaped = FrameAccess.frameHasEscapedContext(frame) ? "$" : " ";
        final String modified = FrameAccess.frameHasModifiedSender(frame) ? "M" : " ";
        final String unwind = FrameAccess.isUnwindMarkedNonClosure(frame) ? ">> " : "   ";
        final String terminated = FrameAccess.frameHasTerminated(frame) ? "!" : " ";
        final String target = (context != null && homeContext == context) ? "**" : "  ";
        System.out.println(MiscUtils.format("%s%s%s%s%s %s %s%s [context: %s, sender: %s]", target, unwind, terminated, escaped, modified, marker, prefix, code, context, senderMarkerOrNil));
    }

}
