package com.example.communityspring;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunityOpenTelemetry2SpringBootInspectTest {

  @Test
  void printSpanNamesForDefaultAndOverrideCases() throws Exception {
    printScenario("defaultFromApplicationYml", null);
    printScenario("overrideNonMatching", "doesNotMatchAnything*");
    printScenario("overrideStringExclude", "process*,to*");
  }

  private void printScenario(String label, String excludePatterns) throws Exception {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .buildAndRegisterGlobal();

    Path inputDir = Files.createTempDirectory("camel-otel2-spring-inspect");
    ConfigurableApplicationContext context = null;

    try {
      SpringApplicationBuilder builder = new SpringApplicationBuilder(CommunitySpringBootApplication.class)
          .properties(
              "app.source-uri=file:" + inputDir + "?noop=true&initialDelay=0&delay=10",
              "camel.opentelemetry2.enabled=true",
              "camel.opentelemetry2.trace-processors=true");
      if (excludePatterns != null) {
        builder.properties("camel.opentelemetry2.exclude-patterns=" + excludePatterns);
      }

      context = builder.run();
      CamelContext camelContext = context.getBean(CamelContext.class);
      CountDownLatch latch = context.getBean(CountDownLatch.class);
      NotifyBuilder notify = new NotifyBuilder(camelContext).whenDone(1).create();

      Files.writeString(inputDir.resolve("sample.txt"), "hello");

      assertTrue(notify.matches(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS));
      assertTrue(latch.await(5, TimeUnit.SECONDS));

      Map<String, Long> counts = exporter.getFinishedSpanItems().stream()
          .collect(Collectors.groupingBy(SpanData::getName, TreeMap::new, Collectors.counting()));
      System.out.println("=== " + label + " ===");
      System.out.println("excludePatterns=" + excludePatterns);
      System.out.println("traceCount=" + exporter.getFinishedSpanItems().stream().map(SpanData::getTraceId).distinct().count());
      System.out.println("spanCount=" + exporter.getFinishedSpanItems().size());
      System.out.println("spanNames=" + counts);
    } finally {
      if (context != null) {
        context.close();
      }
      sdk.close();
      GlobalOpenTelemetry.resetForTest();
      tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
    }
  }
}
