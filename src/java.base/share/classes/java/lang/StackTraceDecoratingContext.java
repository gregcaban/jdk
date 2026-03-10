package java.lang;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * A decorating context that associates opaque metadata with a specific
 * method for display in stack traces. Contexts form a linked list
 * managed by {@link Thread}.
 *
 * @since 27
 */
public final class StackTraceDecoratingContext {
    final Method method;
    final Object metadata;
    StackTraceDecoratingContext next; // package-private, managed by Thread

    /**
     * Creates a new decorating context.
     *
     * @param method   the method this context targets
     * @param metadata opaque metadata to render in stack traces
     */
    public StackTraceDecoratingContext(Method method, Object metadata) {
        this.method = Objects.requireNonNull(method);
        this.metadata = metadata;
    }

    /**
     * Returns the target method.
     *
     * @return the method this context targets
     */
    public Method method()   { return method; }

    /**
     * Returns the opaque metadata.
     *
     * @return the metadata object
     */
    public Object metadata() { return metadata; }
}
