package com.example.repro;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import org.apache.camel.CamelContext;
import org.apache.camel.opentelemetry.OpenTelemetryTracer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CamelOpenTelemetryProperties.class)
@ConditionalOnProperty(value = "camel.opentelemetry.enabled", havingValue = "true", matchIfMissing = true)
public class CamelOpenTelemetryConfiguration {

  @Bean(initMethod = "", destroyMethod = "")
  @ConditionalOnMissingBean(OpenTelemetryTracer.class)
  OpenTelemetryTracer openTelemetryTracer(
      CamelContext camelContext,
      CamelOpenTelemetryProperties properties,
      Tracer tracer,
      ContextPropagators contextPropagators) {
    return CamelOpenTelemetrySupport.createTracer(
        camelContext,
        tracer,
        properties.getExcludePatternsAsString(),
        properties.isPropagateContext(),
        contextPropagators);
  }
}
