package com.example.repro;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import org.apache.camel.opentelemetry.OpenTelemetryTracer;
import org.apache.camel.opentelemetry.OpenTelemetryTracingStrategy;

public final class CamelOpenTelemetrySupport {

  private CamelOpenTelemetrySupport() {
  }

  public static OpenTelemetryTracer createTracer(
      Tracer tracer,
      String excludePatterns,
      boolean propagateContext,
      ContextPropagators contextPropagators) {
    OpenTelemetryTracer camelTracer = new OpenTelemetryTracer();
    if (tracer != null) {
      camelTracer.setTracer(tracer);
    }
    if (contextPropagators != null) {
      camelTracer.setContextPropagators(contextPropagators);
    }
    camelTracer.setExcludePatterns(excludePatterns);

    OpenTelemetryTracingStrategy strategy = new OpenTelemetryTracingStrategy(camelTracer);
    strategy.setPropagateContext(propagateContext);
    camelTracer.setTracingStrategy(strategy);
    return camelTracer;
  }
}
