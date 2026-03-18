package com.example.repro;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CamelExcludePatternsPropagationTest {

  @Test
  void excludedProcessorWithoutPropagateContextSplitsTraceAcrossAsyncTask() throws Exception {
    CamelExcludePatternsScenarioRunner.ScenarioResult result =
        CamelExcludePatternsScenarioRunner.runScenario("process*", false);

    assertEquals(2, result.traceIds().size(),
        "excludePatterns 有効時に propagateContext=false だと trace が分岐する");
    assertTrue(result.spanNames().contains("async-custom-span"));
    assertFalse(result.spanNames().contains("processAsync"),
        "excludePatterns に一致した processor span は作成されない");
  }

  @Test
  void propagateContextKeepsSingleTraceWhileExcludingProcessorSpan() throws Exception {
    CamelExcludePatternsScenarioRunner.ScenarioResult result =
        CamelExcludePatternsScenarioRunner.runScenario("process*", true);

    assertEquals(1, result.traceIds().size(),
        "propagateContext=true なら custom span も同一 trace に入る");
    assertTrue(result.spanNames().contains("async-custom-span"));
    assertTrue(result.spanNames().contains("afterDelay"),
        "非除外 processor は trace-processors 相当の span が作成される");
    assertFalse(result.spanNames().contains("processAsync"),
        "propagateContext=true でも excludePatterns は維持される");
  }

}
