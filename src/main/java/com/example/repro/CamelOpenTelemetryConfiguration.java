package com.example.repro;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import org.apache.camel.opentelemetry.OpenTelemetryTracer;
import org.apache.camel.spring.boot.CamelContextConfiguration;
import org.springframework.beans.factory.ObjectProvider;
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
      CamelOpenTelemetryProperties properties,
      ObjectProvider<Tracer> tracerProvider,
      ObjectProvider<ContextPropagators> contextPropagatorsProvider) {
    return CamelOpenTelemetrySupport.createTracer(
        tracerProvider.getIfAvailable(),
        properties.getExcludePatternsAsString(),
        properties.isPropagateContext(),
        contextPropagatorsProvider.getIfAvailable());
  }

  @Bean
  CamelContextConfiguration camelOpenTelemetryInitializer(OpenTelemetryTracer openTelemetryTracer) {
    return new CamelContextConfiguration() {
      @Override
      public void beforeApplicationStart(org.apache.camel.CamelContext camelContext) {
        openTelemetryTracer.init(camelContext);
      }

      @Override
      public void afterApplicationStart(org.apache.camel.CamelContext camelContext) {
        // noop
      }
    };
  }
}
