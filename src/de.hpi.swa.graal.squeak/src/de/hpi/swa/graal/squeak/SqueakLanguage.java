package de.hpi.swa.graal.squeak;

import java.io.PrintWriter;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;

import de.hpi.swa.graal.squeak.image.SqueakImageContext;
import de.hpi.swa.graal.squeak.model.FrameMarker;
import de.hpi.swa.graal.squeak.nodes.SqueakGuards;
import de.hpi.swa.graal.squeak.nodes.SqueakRootNode;
import de.hpi.swa.graal.squeak.nodes.context.SqueakLookupClassNode;

@TruffleLanguage.Registration(id = SqueakLanguage.ID, name = SqueakLanguage.NAME, version = SqueakLanguage.VERSION, mimeType = SqueakLanguage.MIME_TYPE, interactive = true, internal = false)
@ProvidedTags({StandardTags.CallTag.class, StandardTags.RootTag.class, StandardTags.StatementTag.class, DebuggerTags.AlwaysHalt.class})
public final class SqueakLanguage extends TruffleLanguage<SqueakImageContext> {
    public static final String ID = "squeaksmalltalk";
    public static final String NAME = "Squeak/Smalltalk";
    public static final String MIME_TYPE = "application/x-squeak-smalltalk";
    public static final String VERSION = "0.1";

    @Override
    protected SqueakImageContext createContext(final Env env) {
        final PrintWriter out = new PrintWriter(env.out(), true);
        final PrintWriter err = new PrintWriter(env.err(), true);
        return new SqueakImageContext(this, env, out, err);
    }

    @Override
    protected CallTarget parse(final ParsingRequest request) throws Exception {
        return Truffle.getRuntime().createCallTarget(SqueakRootNode.create(this, request));
    }

    @Override
    protected boolean isObjectOfLanguage(final Object object) {
        return SqueakGuards.isAbstractSqueakObject(object);
    }

    @Override
    protected Object findMetaObject(final SqueakImageContext image, final Object value) {
        // TODO: return ContextObject instead?
        if (value instanceof FrameMarker) {
            return image.nilClass;
        }
        return SqueakLookupClassNode.create(image).executeLookup(value);
    }

    public static SqueakImageContext getContext() {
        return getCurrentContext(SqueakLanguage.class);
    }
}
