package com.example.repro;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CamelOpenTelemetryPropertiesBindingTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
          ConfigurationPropertiesAutoConfiguration.class))
      .withUserConfiguration(TestConfiguration.class);

  @Test
  void bindsExcludePatternsFromIndexedProperties() {
    contextRunner
        .withPropertyValues(
            "camel.opentelemetry.enabled=true",
            "camel.opentelemetry.exclude-patterns[0]=^(process|to).*",
            "camel.opentelemetry.propagate-context=true")
        .run(context -> {
          CamelOpenTelemetryProperties properties = context.getBean(CamelOpenTelemetryProperties.class);
          assertEquals(1, properties.getExcludePatterns().size());
          assertEquals("^(process|to).*", properties.getExcludePatterns().get(0));
          assertEquals("^(process|to).*", properties.getExcludePatternsAsString());
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(CamelOpenTelemetryProperties.class)
  static class TestConfiguration {
  }
}
