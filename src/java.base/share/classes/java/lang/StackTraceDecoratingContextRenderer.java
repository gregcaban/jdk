package java.lang;

/**
 * A renderer that converts opaque metadata from a
 * {@link StackTraceDecoratingContext} into a string for display
 * in stack traces.
 *
 * @since 27
 */
@FunctionalInterface
public interface StackTraceDecoratingContextRenderer {
    /**
     * Renders the metadata associated with a decorating context
     * into a string for display in stack traces.
     *
     * @param metadata the opaque metadata object
     * @return a string representation, or null to suppress decoration
     */
    String render(Object metadata);
}
