# Welcome to the JDK!

For build instructions please see the
[online documentation](https://git.openjdk.org/jdk/blob/master/doc/building.md),
or either of these files:

- [doc/building.html](doc/building.html) (html version)
- [doc/building.md](doc/building.md) (markdown version)

See <https://openjdk.org/> for more information about the OpenJDK
Community and the JDK and see <https://bugs.openjdk.org> for JDK issue
tracking.

## Stack Trace Decorating Context

This fork extends OpenJDK with a stack trace decorating context feature, allowing JVM applications to attach runtime metadata (trace IDs, span IDs, arbitrary attributes) to individual stack frames. It is the JDK side of the blog series [Decorating JVM Stack Traces for Fun and Profit](https://gregcaban.github.io/blog/jvm-stack-decorator). The companion demo application is at [jvm-stack-trace-decorator-demo](https://github.com/gregcaban/jvm-stack-trace-decorator-demo).

| Branch | Blog Post |
|--------|-----------|
| [`feature/simplest-decorator`](https://github.com/gregcaban/jdk/tree/feature/simplest-decorator) | [Part 2 — The Simplest Decorator](https://gregcaban.github.io/blog/jvm-stack-decorator-part-2) |
| [`feature/otel-decorator`](https://github.com/gregcaban/jdk/tree/feature/otel-decorator) | [Part 3 — OTel-like Annotation](https://gregcaban.github.io/blog/jvm-stack-decorator-part-3) |
