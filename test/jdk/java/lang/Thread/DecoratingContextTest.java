/*
 * @test
 * @summary Test rich stack trace decorating context
 * @run main DecoratingContextTest
 */

import java.lang.reflect.Method;
import java.util.Map;

public class DecoratingContextTest {

    // ---- setup: register global renderer ----
    static {
        Thread.setStackTraceDecoratingContextRenderer(metadata -> {
            if (metadata instanceof String s) return s;
            if (metadata instanceof Map<?,?> m) {
                StringBuilder sb = new StringBuilder();
                m.forEach((k, v) -> {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(k).append('=').append(v);
                });
                return sb.toString();
            }
            return String.valueOf(metadata);
        });
    }

    // ---- helper methods that will appear on the stack ----

    static void methodA() throws Exception {
        methodB();
    }

    static void methodB() throws Exception {
        methodC();
    }

    static void methodC() throws Exception {
        throw new Exception("test");
    }

    static void recursiveMethod(int depth) throws Exception {
        if (depth == 0) throw new Exception("recursive");
        recursiveMethod(depth - 1);
    }

    // ---- tests ----

    public static void main(String[] args) throws Exception {
        testBasicPushAndCapture();
        testMultipleContexts();
        testNoMatch();
        testOwnershipTransfer();
        testNoContext();
        testOtelStyleMetadata();
        testRecursion();
        testNestedTryCatch();
        testNoRenderer();
        testVirtualThreadBasic();
        testVirtualThreadContextSurvivesYield();
        testVirtualThreadIsolationFromCarrier();
        testVirtualThreadMultipleContexts();

        System.out.println("All tests passed.");
    }

    /**
     * Test 1: Push one context, throw, verify matching frame is decorated.
     */
    static void testBasicPushAndCapture() throws Exception {
        Method m = DecoratingContextTest.class
                .getDeclaredMethod("methodC");
        Thread.currentThread().pushDecoratingContext(
                new StackTraceDecoratingContext(m, "POST /api/orders"));

        try {
            methodA();
            throw new AssertionError("Should have thrown");
        } catch (Exception e) {
            StackTraceElement[] trace = e.getStackTrace();
            boolean found = false;
            for (StackTraceElement ste : trace) {
                String s = ste.toString();
                if (ste.getMethodName().equals("methodC")) {
                    assertContains(s, "[POST /api/orders]",
                            "methodC frame should be decorated");
                    found = true;
                } else {
                    assertNotContains(s, "[",
                            "non-matching frame should not be decorated: " + s);
                }
            }
            assertTrue(found, "methodC frame not found in trace");
        }
    }

    /**
     * Test 2: Push contexts for two different methods, verify both
     * decorated. Head = most recently pushed = matches shallowest.
     */
    static void testMultipleContexts() throws Exception {
        Method mA = DecoratingContextTest.class
                .getDeclaredMethod("methodA");
        Method mC = DecoratingContextTest.class
                .getDeclaredMethod("methodC");

        // Push A first, then C. Head is C.
        // Frame walk: methodC (shallowest), methodB, methodA (deepest)
        // C matches methodC first, then A matches methodA.
        Thread.currentThread().pushDecoratingContext(
                new StackTraceDecoratingContext(mA, "span-A"));
        Thread.currentThread().pushDecoratingContext(
                new StackTraceDecoratingContext(mC, "span-C"));

        try {
            methodA();
            throw new AssertionError("Should have thrown");
        } catch (Exception e) {
            StackTraceElement[] trace = e.getStackTrace();
            boolean foundA = false, foundC = false;
            for (StackTraceElement ste : trace) {
                if (ste.getMethodName().equals("methodA")) {
                    assertContains(ste.toString(), "[span-A]",
                            "methodA should have span-A");
                    foundA = true;
                }
                if (ste.getMethodName().equals("methodC")) {
                    assertContains(ste.toString(), "[span-C]",
                            "methodC should have span-C");
                    foundC = true;
                }
            }
            assertTrue(foundA && foundC, "Both frames should be found");
        }
    }

    /**
     * Test 3: Push context for a method not on the stack.
     * All frames should be undecorated.
     */
    static void testNoMatch() throws Exception {
        Method m = DecoratingContextTest.class
                .getDeclaredMethod("recursiveMethod", int.class);
        Thread.currentThread().pushDecoratingContext(
                new StackTraceDecoratingContext(m, "should-not-appear"));

        try {
            methodA(); // recursiveMethod is NOT called
            throw new AssertionError("Should have thrown");
        } catch (Exception e) {
            for (StackTraceElement ste : e.getStackTrace()) {
                assertNotContains(ste.toString(), "[",
                        "No frame should be decorated: " + ste);
            }
        }
    }

    /**
     * Test 4: After exception, Thread's context list should be null.
     */
    static void testOwnershipTransfer() throws Exception {
        Method m = DecoratingContextTest.class
                .getDeclaredMethod("methodC");
        Thread.currentThread().pushDecoratingContext(
                new StackTraceDecoratingContext(m, "transfer-test"));

        try {
            methodA();
        } catch (Exception e) {
            // Context was transferred to the exception
        }

        // Thread should have no context now
        StackTraceDecoratingContext remaining =
                Thread.currentThread().getDecoratingContext();
        assertTrue(remaining == null,
                "Thread context should be null after exception");
    }

    /**
     * Test 5: Normal exception without any context push.
     * Should behave exactly as before.
     */
    static void testNoContext() throws Exception {
        // Ensure thread has no context
        Thread.currentThread().takeDecoratingContext();

        try {
            methodA();
        } catch (Exception e) {
            for (StackTraceElement ste : e.getStackTrace()) {
                assertNotContains(ste.toString(), "[",
                        "No decoration expected: " + ste);
            }
        }
    }

    /**
     * Test 6: OTEL-style map metadata rendered correctly.
     */
    static void testOtelStyleMetadata() throws Exception {
        Method m = DecoratingContextTest.class
                .getDeclaredMethod("methodC");
        Map<String, String> otelData = Map.of(
                "traceId", "abc123",
                "spanId", "def456"
        );
        Thread.currentThread().pushDecoratingContext(
                new StackTraceDecoratingContext(m, otelData));

        try {
            methodA();
        } catch (Exception e) {
            StackTraceElement[] trace = e.getStackTrace();
            for (StackTraceElement ste : trace) {
                if (ste.getMethodName().equals("methodC")) {
                    String s = ste.toString();
                    assertContains(s, "traceId=abc123",
                            "Should contain traceId");
                    assertContains(s, "spanId=def456",
                            "Should contain spanId");
                    return;
                }
            }
            throw new AssertionError("methodC not found");
        }
    }

    /**
     * Test 7: Recursive method -- one context, multiple frames with
     * same method. Head matches shallowest (first encountered during
     * top-down frame walk).
     */
    static void testRecursion() throws Exception {
        Method m = DecoratingContextTest.class
                .getDeclaredMethod("recursiveMethod", int.class);
        Thread.currentThread().pushDecoratingContext(
                new StackTraceDecoratingContext(m, "matched"));

        try {
            recursiveMethod(3);
        } catch (Exception e) {
            StackTraceElement[] trace = e.getStackTrace();
            int decoratedCount = 0;
            int decoratedIndex = -1;
            for (int i = 0; i < trace.length; i++) {
                if (trace[i].getMethodName().equals("recursiveMethod")
                        && trace[i].toString().contains("[matched]")) {
                    decoratedCount++;
                    decoratedIndex = i;
                }
            }
            assertTrue(decoratedCount == 1,
                    "Exactly one frame should be decorated, got: "
                    + decoratedCount);
            // Should be the shallowest (lowest index) recursiveMethod frame
            for (int i = 0; i < trace.length; i++) {
                if (trace[i].getMethodName().equals("recursiveMethod")) {
                    assertTrue(i == decoratedIndex,
                            "Shallowest recursiveMethod frame should be "
                            + "decorated");
                    break;
                }
            }
        }
    }

    /**
     * Test 8: Nested try-catch -- inner exception gets first list,
     * outer exception gets fresh list.
     */
    static void testNestedTryCatch() throws Exception {
        Method mC = DecoratingContextTest.class
                .getDeclaredMethod("methodC");
        Thread.currentThread().pushDecoratingContext(
                new StackTraceDecoratingContext(mC, "inner-span"));

        Exception inner = null;
        try {
            methodA(); // throws, transfers context to exception
        } catch (Exception e) {
            inner = e;
        }

        // Thread context should be null now -- push new for outer
        Method mA = DecoratingContextTest.class
                .getDeclaredMethod("methodA");
        Thread.currentThread().pushDecoratingContext(
                new StackTraceDecoratingContext(mA, "outer-span"));

        Exception outer = null;
        try {
            methodA();
        } catch (Exception e) {
            outer = e;
        }

        // inner should have "inner-span" on methodC
        boolean foundInner = false;
        for (StackTraceElement ste : inner.getStackTrace()) {
            if (ste.getMethodName().equals("methodC")) {
                assertContains(ste.toString(), "[inner-span]",
                        "Inner exception's methodC should have inner-span");
                foundInner = true;
            }
        }
        assertTrue(foundInner, "methodC not found in inner trace");

        // outer should have "outer-span" on methodA
        boolean foundOuter = false;
        for (StackTraceElement ste : outer.getStackTrace()) {
            if (ste.getMethodName().equals("methodA")) {
                assertContains(ste.toString(), "[outer-span]",
                        "Outer exception's methodA should have outer-span");
                foundOuter = true;
            }
        }
        assertTrue(foundOuter, "methodA not found in outer trace");
    }

    /**
     * Test 9: With no renderer set, metadata is captured but
     * toString() does not render it.
     */
    static void testNoRenderer() throws Exception {
        // Save and clear renderer
        var saved = Thread.getStackTraceDecoratingContextRenderer();
        Thread.setStackTraceDecoratingContextRenderer(null);

        try {
            Method m = DecoratingContextTest.class
                    .getDeclaredMethod("methodC");
            Thread.currentThread().pushDecoratingContext(
                    new StackTraceDecoratingContext(m, "invisible"));

            try {
                methodA();
            } catch (Exception e) {
                for (StackTraceElement ste : e.getStackTrace()) {
                    assertNotContains(ste.toString(), "[",
                            "No rendering without renderer: " + ste);
                }
            }
        } finally {
            // Restore renderer
            Thread.setStackTraceDecoratingContextRenderer(saved);
        }
    }

    /**
     * Test 10: Push context on a virtual thread, throw, verify decoration.
     */
    static void testVirtualThreadBasic() throws Exception {
        var result = new java.util.concurrent.CompletableFuture<StackTraceElement[]>();

        Thread.ofVirtual().name("vthread-test-basic").start(() -> {
            try {
                Method m = DecoratingContextTest.class.getDeclaredMethod("methodC");
                Thread.currentThread().pushDecoratingContext(
                        new StackTraceDecoratingContext(m, "vthread-span"));
                methodA();
            } catch (Exception e) {
                result.complete(e.getStackTrace());
            }
        });

        StackTraceElement[] trace = result.get();
        boolean found = false;
        for (StackTraceElement ste : trace) {
            if (ste.getMethodName().equals("methodC")) {
                assertContains(ste.toString(), "[vthread-span]",
                        "methodC on vthread should be decorated");
                found = true;
            }
        }
        assertTrue(found, "methodC not found in vthread trace");
    }

    /**
     * Test 11: Context survives a yield point (Thread.sleep triggers
     * virtual thread unmount/remount, potentially on a different carrier).
     */
    static void testVirtualThreadContextSurvivesYield() throws Exception {
        var result = new java.util.concurrent.CompletableFuture<StackTraceElement[]>();

        Thread.ofVirtual().name("vthread-test-yield").start(() -> {
            try {
                Method m = DecoratingContextTest.class.getDeclaredMethod("methodC");
                Thread.currentThread().pushDecoratingContext(
                        new StackTraceDecoratingContext(m, "survived-yield"));

                // Sleep triggers virtual thread unmount from carrier.
                // When resumed (possibly on a different carrier), the
                // decorating context should still be on this virtual thread.
                Thread.sleep(10);

                methodA();
            } catch (Exception e) {
                result.complete(e.getStackTrace());
            }
        });

        StackTraceElement[] trace = result.get();
        boolean found = false;
        for (StackTraceElement ste : trace) {
            if (ste.getMethodName().equals("methodC")) {
                assertContains(ste.toString(), "[survived-yield]",
                        "Context should survive yield point");
                found = true;
            }
        }
        assertTrue(found, "methodC not found after yield");
    }

    /**
     * Test 12: Context pushed on a virtual thread does NOT leak to
     * the carrier thread, and vice versa.
     */
    static void testVirtualThreadIsolationFromCarrier() throws Exception {
        // Clear carrier's context
        Thread.currentThread().takeDecoratingContext();

        // Push on carrier
        Method mA = DecoratingContextTest.class.getDeclaredMethod("methodA");
        Thread.currentThread().pushDecoratingContext(
                new StackTraceDecoratingContext(mA, "carrier-ctx"));

        var result = new java.util.concurrent.CompletableFuture<StackTraceDecoratingContext>();

        Thread.ofVirtual().name("vthread-test-isolation").start(() -> {
            // Virtual thread should NOT see carrier's context
            result.complete(Thread.currentThread().getDecoratingContext());
        });

        StackTraceDecoratingContext vtCtx = result.get();
        assertTrue(vtCtx == null,
                "Virtual thread should not inherit carrier's context");

        // Clean up carrier
        Thread.currentThread().takeDecoratingContext();
    }

    /**
     * Test 13: Multiple contexts on a virtual thread, matching
     * different frames.
     */
    static void testVirtualThreadMultipleContexts() throws Exception {
        var result = new java.util.concurrent.CompletableFuture<StackTraceElement[]>();

        Thread.ofVirtual().name("vthread-test-multi").start(() -> {
            try {
                Method mA = DecoratingContextTest.class.getDeclaredMethod("methodA");
                Method mC = DecoratingContextTest.class.getDeclaredMethod("methodC");

                Thread.currentThread().pushDecoratingContext(
                        new StackTraceDecoratingContext(mA, "vt-outer"));
                Thread.currentThread().pushDecoratingContext(
                        new StackTraceDecoratingContext(mC, "vt-inner"));

                // Sleep to force at least one yield
                Thread.sleep(1);

                methodA();
            } catch (Exception e) {
                result.complete(e.getStackTrace());
            }
        });

        StackTraceElement[] trace = result.get();
        boolean foundA = false, foundC = false;
        for (StackTraceElement ste : trace) {
            if (ste.getMethodName().equals("methodA")
                    && ste.toString().contains("[vt-outer]")) {
                foundA = true;
            }
            if (ste.getMethodName().equals("methodC")
                    && ste.toString().contains("[vt-inner]")) {
                foundC = true;
            }
        }
        assertTrue(foundA && foundC,
                "Both vthread contexts should match their frames");
    }

    // ---- assertion helpers ----

    static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    static void assertContains(String haystack, String needle, String msg) {
        if (!haystack.contains(needle)) {
            throw new AssertionError(msg + "\n  expected to contain: "
                    + needle + "\n  actual: " + haystack);
        }
    }

    static void assertNotContains(String haystack, String needle,
                                   String msg) {
        if (haystack.contains(needle)) {
            throw new AssertionError(msg);
        }
    }
}
