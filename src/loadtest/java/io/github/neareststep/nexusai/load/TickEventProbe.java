package io.github.neareststep.nexusai.load;

/**
 * Paper exposes MSPT through {@code ServerTickEndEvent#getTickDuration}.
 * The load driver refuses to enable when that class is missing, instead of reporting zeros.
 */
public final class TickEventProbe {

    public static final String CLASS_NAME = "com.destroystokyo.paper.event.server.ServerTickEndEvent";

    public static final String MISSING = "NexusAI-LoadDriver requires Paper ServerTickEndEvent#getTickDuration. "
            + "This server does not provide " + CLASS_NAME + ". Refusing to enable.";

    private TickEventProbe() {
    }

    /**
     * @return {@code null} when the event class loads, otherwise {@link #MISSING}
     */
    public static String failureIfMissing(ClassLoader loader) {
        ClassLoader source = loader == null ? TickEventProbe.class.getClassLoader() : loader;
        try {
            Class.forName(CLASS_NAME, false, source);
            return null;
        } catch (ClassNotFoundException | LinkageError ex) {
            return MISSING;
        }
    }
}
