package com.example.communityspring;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PreDestroy;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CommunityRouteConfiguration {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  @Bean
  RouteBuilder routeBuilder(
      @Value("${app.source-uri}") String sourceUri,
      CountDownLatch asyncCompletedLatch) {
    Tracer tracer = GlobalOpenTelemetry.getTracer("community-spring-test");

    return new RouteBuilder() {
      @Override
      public void configure() {
        from(sourceUri).routeId("sourceRoute")
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
                  asyncCompletedLatch.countDown();
                }
              });

              Future<?> future = executor.submit(task);
              future.get(5, TimeUnit.SECONDS);
            }).id("processAsync")
            .delay(constant(10)).id("afterDelay");
      }
    };
  }

  @PreDestroy
  void shutdownExecutor() {
    executor.shutdownNow();
  }
}
