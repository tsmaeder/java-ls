# Plan items (ranked by impact)

- [X] P1 — Checkpoint.succeed() (and similar test-helper methods) not resolved · 388 / — / 18 Dominant single problem: cannot find symbol — method succeed() on io.vertx.test.core.Checkpoint. Likely method resolution against an index type. Plan: find why instance methods on Checkpoint (and name(), pipe(), testComplete() — P-tail) aren't found.

- [x] P2 — @Override falsely rejected · 47 / 20 / 21 method does not override or implement a method from a supertype. Fixed indexed raw-type erasure (`IndexClassReader` now uses `types.erasure` for bare `TypeRef`s) and `ClasspathOrder.UNRESTRICTED` pick (was always null, causing `java.lang.*` fallback). Genuine vert.x impl overrides (VerticleBase, ConnectionBase.metrics, EventBusImpl.nextHandler, Http1/Http2 connections, ClusteredEventBus) now clean. Residual ~21 are P7 jackson-v3 cascade; ~4 are SimpleConnectionPool inner classes + Http2Multiplex (likely P4 `java.lang.*` cascade on interface param types).

- [ ]P3 — JDK & internal types reported "not public … cannot be accessed from outside package" · 225 / 38 / 49 Clear visibility bug: java.util.List, java.util.function.Function, java.util.concurrent.TimeUnit, java.time.Instant, java.util.stream.IntStream, CompletableFuture.result, plus package-private nested types (Executor.Action, AltSvc.ListOfValue, HttpConfigurator.Http1x/H2). Plan: fix access-flag/visibility computation for index/JDK (jrt) entries.

- [ ] P4 — Types wrongly resolved to phantom java.lang.* · 105 / 20 / 40 incompatible types: java.lang.Stream / java.lang.ContextInternal / java.lang.ReportMode / java.lang.Handler … cannot be converted to <real fqn>, plus java.lang.Handler is not a functional interface. Plan: the resolver appears to fall back to java.lang for unresolved imported/nested types — fix import/nested-type resolution.

- [ ] P5 — Custom annotation types not recognized as annotations · 78 / 5 / 21 incompatible types: io.vertx.test.proxy.WithProxy / Repeat / ProvidedBy / WithDnsServer cannot be converted to java.lang.annotation.Annotation. Same family as P4 (annotation type resolution). Plan: ensure annotation declarations resolve to @interface symbols.

- [ ] P6 — Method/constructor overloads not applicable · 98 / 44 / 32 e.g. method await in VertxTestBase cannot be applied to given types, no suitable constructor found for JsonArray(List<Object>), of(HttpVersion). Often downstream of P3/P4 (wrong arg types). Plan: assess how much is independent vs. fallout.

- [ ] P7 — Jackson 3 (tools.jackson.*) packages/modules missing · 78 / 18 / 21 (+ module not found 19) package tools.jackson.core/.databind/.core.util does not exist, package JsonTokenId does not exist, missing modules. Plan: determine whether the tools.jackson (Jackson 3) dependency is genuinely absent from the index classpath or simply not indexed.

- [ ] P8 — Other incompatible-types mismatches · 85 / 51 / 48 Long tail of prob.found.req not covered by P4/P5; many likely cascade from P3/P4. Plan: re-triage after P3/P4 fixes.

- [ ] P9 — Remaining "cannot find symbol" · ~470 / ~180 / many Everything in category E beyond P1 (e.g. classes JsonParser, BufferRecycler, JsonGenerator, IOException; valueOf(String)). Mix of P7-related Jackson types and genuine resolution gaps. Plan: bucket by missing-symbol kind after P7.

- [ ] P10 — override.incompatible.ret · 12 / 12 / 11 — return-type covariance/override checks. P11 — unreported exception java.lang.Throwable · 8 / 1 / 3 — checked-exception flow. P12 — Small tail · does.not.override.abstract (4), not.within.bounds (4), duplicate.annotation.missing.container (4), improperly.formed.type.inner.raw.param (3), static.imp.only.classes.and.interfaces (3), package.in.other.module (2), cant.apply.diamond (2), compiler-internal-error (2), plus singletons (unexpected.lambda, cant.deref, incomparable.types, …).