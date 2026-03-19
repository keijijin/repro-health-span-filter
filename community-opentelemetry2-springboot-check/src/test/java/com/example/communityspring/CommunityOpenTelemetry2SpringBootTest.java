package com.example.communityspring;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.NotifyBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunityOpenTelemetry2SpringBootTest {

  @Test
  void applicationYmlStringExcludePatternsKeepAsyncSpanInSingleTrace() throws Exception {
    ScenarioResult result = runScenario();

    assertEquals(1, result.traceIds().size(),
        "community starter should keep a single trace");
    assertTrue(result.spanNames().contains("async-custom-span"));
  }

  @Test
  void applicationYmlStringExcludePatternsSuppressProcessorAndToSpans() throws Exception {
    ScenarioResult result = runScenario();

    assertFalse(result.spanNames().contains("processValidate-process"));
    assertFalse(result.spanNames().contains("processAsync-process"));
    assertFalse(result.spanNames().contains("toInternal-to"));
    assertTrue(result.spanNames().contains("afterDelay-delay"));
    assertTrue(result.spanNames().contains("timer"));
    assertTrue(result.spanNames().contains("internal"));
  }

  private ScenarioResult runScenario() throws Exception {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .buildAndRegisterGlobal();

    Path inputDir = Files.createTempDirectory("camel-otel2-spring-input");
    ConfigurableApplicationContext context = null;

    try {
      SpringApplicationBuilder builder = new SpringApplicationBuilder(CommunitySpringBootApplication.class)
          .properties(
              "app.source-uri=file:" + inputDir + "?noop=true&initialDelay=0&delay=10",
              "camel.opentelemetry2.enabled=true",
              "camel.opentelemetry2.trace-processors=true");

      context = builder.run();

      CamelContext camelContext = context.getBean(CamelContext.class);
      CountDownLatch latch = context.getBean(CountDownLatch.class);
      NotifyBuilder notify = new NotifyBuilder(camelContext).whenDone(1).create();

      Files.writeString(inputDir.resolve("sample.txt"), "hello");

      assertTrue(notify.matches(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS),
          "Camel route did not finish in time");
      assertTrue(latch.await(5, TimeUnit.SECONDS),
          "async custom span was not created in time");

      return new ScenarioResult(exporter.getFinishedSpanItems());
    } finally {
      if (context != null) {
        context.close();
      }
      sdk.close();
      GlobalOpenTelemetry.resetForTest();
      tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
    }
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
