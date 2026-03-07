/*
 * @test
 * @summary Test Thread.setDecoratingContext decorates stack trace class names
 * @run main DecoratingContextTest
 */
public class DecoratingContextTest {
    public static void main(String[] args) {
        // Test with decorating context set
        Thread.currentThread().setDecoratingContext("POST /api/orders");
        try {
            throwException();
        } catch (Exception e) {
            StackTraceElement[] trace = e.getStackTrace();
            for (StackTraceElement ste : trace) {
                if (!ste.getClassName().startsWith("POST /api/orders/")) {
                    throw new AssertionError(
                        "Expected prefix 'POST /api/orders/' but got: " + ste.getClassName());
                }
            }
        }

        // Test with decorating context cleared
        Thread.currentThread().setDecoratingContext(null);
        try {
            throwException();
        } catch (Exception e) {
            StackTraceElement[] trace = e.getStackTrace();
            for (StackTraceElement ste : trace) {
                if (ste.getClassName().contains("/")) {
                    throw new AssertionError(
                        "Expected no prefix but got: " + ste.getClassName());
                }
            }
        }

        System.out.println("All assertions passed.");
    }

    private static void throwException() throws Exception {
        throw new Exception("test");
    }
}
