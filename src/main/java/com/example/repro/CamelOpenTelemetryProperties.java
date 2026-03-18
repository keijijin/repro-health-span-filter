package com.example.repro;

import java.util.List;
import java.util.StringJoiner;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "camel.opentelemetry")
public class CamelOpenTelemetryProperties {

  private boolean enabled = true;
  private List<String> excludePatterns;
  private boolean propagateContext;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public List<String> getExcludePatterns() {
    return excludePatterns;
  }

  public void setExcludePatterns(List<String> excludePatterns) {
    this.excludePatterns = excludePatterns;
  }

  public String getExcludePatternsAsString() {
    if (excludePatterns == null || excludePatterns.isEmpty()) {
      return null;
    }

    StringJoiner joiner = new StringJoiner(",");
    excludePatterns.stream()
        .filter(pattern -> pattern != null && !pattern.isBlank())
        .forEach(joiner::add);
    String joined = joiner.toString();
    return joined.isBlank() ? null : joined;
  }

  public boolean isPropagateContext() {
    return propagateContext;
  }

  public void setPropagateContext(boolean propagateContext) {
    this.propagateContext = propagateContext;
  }
}
