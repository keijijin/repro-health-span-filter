package com.example.community;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.NotifyBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.opentelemetry2.OpenTelemetryTracer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunityOpenTelemetry2Test {

  @Test
  void excludePatternsStillWorkWhenTraceProcessorsIsEnabled() throws Exception {
    ScenarioResult result = runScenario("process*,to*");

    assertEquals(1, result.traceIds().size(),
        "community camel-opentelemetry2 should keep a single trace");
    assertFalse(result.spanNames().contains("processAsync-process"),
        "processAsync processor span should be excluded");
    assertFalse(result.spanNames().contains("toInternal-to"),
        "toInternal producer span should be excluded");
    assertTrue(result.spanNames().contains("async-custom-span"),
        "custom async span should still be present");
  }

  @Test
  void compareSpanCountWithAndWithoutExcludePatterns() throws Exception {
    ScenarioResult withoutExclude = runScenario(null);
    ScenarioResult withExclude = runScenario("process*,to*");

    assertTrue(withoutExclude.spanNames().contains("processAsync-process"));
    assertFalse(withExclude.spanNames().contains("processAsync-process"));
    assertTrue(withoutExclude.spanNames().contains("toInternal-to"));
    assertFalse(withExclude.spanNames().contains("toInternal-to"));
    assertTrue(withoutExclude.spanCount() > withExclude.spanCount(),
        "excludePatterns should reduce total span count in community camel-opentelemetry2");
  }

  private ScenarioResult runScenario(String excludePatterns) throws Exception {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .build();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    CamelContext camelContext = new DefaultCamelContext();
    Path inputDir = Files.createTempDirectory("camel-otel2-input");
    CountDownLatch asyncCompleted = new CountDownLatch(1);

    try {
      Tracer tracer = sdk.getTracer("community-test");
      OpenTelemetryTracer camelTracer = new OpenTelemetryTracer();
      camelTracer.setTraceProcessors(true);
      camelTracer.setExcludePatterns(excludePatterns);
      setOpenTelemetryTracer(camelTracer, tracer);
      setContextPropagators(camelTracer, ContextPropagators.noop());
      camelTracer.init(camelContext);

      camelContext.addRoutes(new RouteBuilder() {
        @Override
        public void configure() {
          from(fileUri(inputDir)).routeId("fileRoute")
              .process(exchange -> {
                // noop
              }).id("processValidate")
              .to("direct:internal").id("toInternal");

          from("direct:internal").routeId("internalRoute")
              .process(exchange -> {
                Runnable task = Context.current().wrap(() -> {
                  Span span = tracer.spanBuilder("async-custom-span")
                      .setParent(Context.current())
                      .startSpan();
                  try (Scope ignored = span.makeCurrent()) {
                    // noop
                  } finally {
                    span.end();
                    asyncCompleted.countDown();
                  }
                });

                Future<?> future = executor.submit(task);
                future.get(5, TimeUnit.SECONDS);
              }).id("processAsync")
              .delay(constant(10)).id("afterDelay");
        }
      });

      NotifyBuilder notify = new NotifyBuilder(camelContext).whenDone(1).create();
      camelContext.start();
      Files.writeString(inputDir.resolve("sample.txt"), "hello");

      assertTrue(notify.matches(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS),
          "Camel route did not finish in time");
      assertTrue(asyncCompleted.await(5, TimeUnit.SECONDS),
          "async custom span was not created in time");

      return new ScenarioResult(exporter.getFinishedSpanItems());
    } finally {
      camelContext.stop();
      executor.shutdownNow();
      tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
    }
  }

  private static void setOpenTelemetryTracer(OpenTelemetryTracer camelTracer, Tracer tracer) throws Exception {
    Method method = OpenTelemetryTracer.class.getDeclaredMethod("setTracer", Tracer.class);
    method.setAccessible(true);
    method.invoke(camelTracer, tracer);
  }

  private static void setContextPropagators(OpenTelemetryTracer camelTracer, ContextPropagators propagators)
      throws Exception {
    Method method = OpenTelemetryTracer.class.getDeclaredMethod("setContextPropagators", ContextPropagators.class);
    method.setAccessible(true);
    method.invoke(camelTracer, propagators);
  }

  private static String fileUri(Path directory) {
    return "file:" + directory.toAbsolutePath() + "?noop=true&initialDelay=0&delay=10";
  }

  private record ScenarioResult(List<SpanData> spans) {
    Set<String> traceIds() {
      return spans.stream().map(SpanData::getTraceId).collect(Collectors.toSet());
    }

    Set<String> spanNames() {
      return spans.stream().map(SpanData::getName).collect(Collectors.toSet());
    }

    long spanCount() {
      return spans.size();
    }
  }
}
