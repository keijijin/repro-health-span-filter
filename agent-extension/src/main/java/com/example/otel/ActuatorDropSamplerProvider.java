package com.example.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.net.URI;
import java.util.List;

public class ActuatorDropSamplerProvider implements AutoConfigurationCustomizerProvider {

  @Override
  public void customize(AutoConfigurationCustomizer customizer) {
    customizer.addSamplerCustomizer((fallback, unusedConfig) -> new ActuatorDropSampler(fallback));
  }

  static final class ActuatorDropSampler implements Sampler {
    private static final AttributeKey<String> HTTP_TARGET = AttributeKey.stringKey("http.target");
    private static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");
    private static final AttributeKey<String> HTTP_URL = AttributeKey.stringKey("http.url");

    private final Sampler fallback;

    private ActuatorDropSampler(Sampler fallback) {
      this.fallback = fallback;
    }

    @Override
    public SamplingResult shouldSample(
        Context parentContext,
        String traceId,
        String name,
        SpanKind spanKind,
        Attributes attributes,
        List<LinkData> parentLinks) {

      if (spanKind == SpanKind.SERVER) {
        String pathCandidate =
            firstNonBlank(
                attributes.get(HTTP_TARGET), attributes.get(HTTP_ROUTE), extractPath(attributes.get(HTTP_URL)), name);

        if (matchesActuatorNoise(pathCandidate)) {
          return SamplingResult.create(SamplingDecision.DROP);
        }
      }

      return fallback.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
    }

    @Override
    public String getDescription() {
      return "ActuatorDropSampler(" + fallback.getDescription() + ")";
    }

    private static boolean matchesActuatorNoise(String v) {
      if (v == null) {
        return false;
      }
      return v.startsWith("/actuator/health")
          || v.startsWith("/actuator/info")
          || v.contains(" /actuator/health")
          || v.contains(" /actuator/info");
    }

    private static String extractPath(String url) {
      if (url == null || url.isBlank()) {
        return null;
      }
      try {
        return URI.create(url).getPath();
      } catch (IllegalArgumentException ignored) {
        return null;
      }
    }

    private static String firstNonBlank(String... values) {
      for (String value : values) {
        if (value != null && !value.isBlank()) {
          return value;
        }
      }
      return null;
    }
  }
}
