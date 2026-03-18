package com.example.repro;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import org.apache.camel.CamelContext;
import org.apache.camel.opentelemetry.OpenTelemetryTracer;
import org.apache.camel.opentelemetry.OpenTelemetryTracingStrategy;

public final class CamelOpenTelemetrySupport {

  private CamelOpenTelemetrySupport() {
  }

  public static OpenTelemetryTracer createTracer(
      CamelContext camelContext,
      Tracer tracer,
      String excludePatterns,
      boolean propagateContext,
      ContextPropagators contextPropagators) {
    OpenTelemetryTracer camelTracer = new OpenTelemetryTracer();
    camelTracer.setTracer(tracer);
    if (contextPropagators != null) {
      camelTracer.setContextPropagators(contextPropagators);
    }
    camelTracer.setExcludePatterns(excludePatterns);

    OpenTelemetryTracingStrategy strategy = new OpenTelemetryTracingStrategy(camelTracer);
    strategy.setPropagateContext(propagateContext);
    camelTracer.setTracingStrategy(strategy);
    camelTracer.init(camelContext);
    return camelTracer;
  }
}
