package com.example.communityspring;

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
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public final class CommunityOpenTelemetry2SpringBootComparisonMain {

  private static final int DEFAULT_MESSAGE_COUNT = 10;

  private CommunityOpenTelemetry2SpringBootComparisonMain() {
  }

  public static void main(String[] args) throws Exception {
    int messageCount = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_MESSAGE_COUNT;

    ScenarioResult defaultFromYaml = runScenario(null, messageCount);
    ScenarioResult overrideNonMatching = runScenario("doesNotMatchAnything*", messageCount);
    ScenarioResult overrideString = runScenario("process*,to*", messageCount);

    printScenario("default_from_application_yml", null, messageCount, defaultFromYaml);
    printScenario("override_non_matching", "doesNotMatchAnything*", messageCount, overrideNonMatching);
    printScenario("override_string_exclude", "process*,to*", messageCount, overrideString);

    System.out.println("=== summary ===");
    System.out.println("default span count                  : " + defaultFromYaml.spanCount());
    System.out.println("non-matching override span count    : " + overrideNonMatching.spanCount());
    System.out.println("string override span count          : " + overrideString.spanCount());
    printDelta("async-custom-span", defaultFromYaml, overrideNonMatching, overrideString);
    printDelta("processValidate-process", defaultFromYaml, overrideNonMatching, overrideString);
    printDelta("processAsync-process", defaultFromYaml, overrideNonMatching, overrideString);
    printDelta("toInternal-to", defaultFromYaml, overrideNonMatching, overrideString);
  }

  private static ScenarioResult runScenario(String excludePatterns, int messageCount) throws Exception {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .buildAndRegisterGlobal();

    ConfigurableApplicationContext context = null;

    try {
      SpringApplicationBuilder builder = new SpringApplicationBuilder(CommunitySpringBootApplication.class)
          .properties(
              "app.expected-count=" + messageCount,
              "camel.opentelemetry2.enabled=true",
              "camel.opentelemetry2.trace-processors=true");
      if (excludePatterns != null) {
        builder.properties("camel.opentelemetry2.exclude-patterns=" + excludePatterns);
      }

      context = builder.run();

      CamelContext camelContext = context.getBean(CamelContext.class);
      CountDownLatch latch = context.getBean(CountDownLatch.class);
      NotifyBuilder notify = new NotifyBuilder(camelContext).whenDone(messageCount).create();

      if (!notify.matches(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("Camel route did not finish in time");
      }
      if (!latch.await(15, TimeUnit.SECONDS)) {
        throw new IllegalStateException("async custom span was not created in time");
      }

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

  private static void printScenario(
      String label,
      String excludePatterns,
      int messageCount,
      ScenarioResult result) {
    System.out.println("=== scenario: " + label + " ===");
    System.out.println("exclude-patterns: " + (excludePatterns == null ? "<application.yml default>" : excludePatterns));
    System.out.println("message count   : " + messageCount);
    System.out.println("trace count     : " + result.traceCount());
    System.out.println("span count      : " + result.spanCount());
    System.out.println("span names      : " + result.spanNameCounts());
  }

  private static void printDelta(
      String spanName,
      ScenarioResult defaultFromYaml,
      ScenarioResult nonMatching,
      ScenarioResult stringOverride) {
    System.out.printf("%-28s default=%d nonMatching=%d stringOverride=%d%n",
        spanName,
        defaultFromYaml.spanNameCount(spanName),
        nonMatching.spanNameCount(spanName),
        stringOverride.spanNameCount(spanName));
  }

  private record ScenarioResult(List<SpanData> spans) {
    long spanCount() {
      return spans.size();
    }

    long spanNameCount(String spanName) {
      return spans.stream().filter(span -> span.getName().equals(spanName)).count();
    }

    long traceCount() {
      return spans.stream().map(SpanData::getTraceId).distinct().count();
    }

    Map<String, Long> spanNameCounts() {
      return spans.stream().collect(Collectors.groupingBy(SpanData::getName, TreeMap::new, Collectors.counting()));
    }
  }
}
