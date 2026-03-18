package com.example.repro;

import java.util.TreeSet;

public final class CamelExcludePatternsComparisonMain {

  private static final int DEFAULT_MESSAGE_COUNT = 200;

  private CamelExcludePatternsComparisonMain() {
  }

  public static void main(String[] args) throws Exception {
    int messageCount = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_MESSAGE_COUNT;

    CamelExcludePatternsScenarioRunner.ScenarioResult withoutExclude =
        CamelExcludePatternsScenarioRunner.runComparisonScenario(null, true, messageCount);
    CamelExcludePatternsScenarioRunner.ScenarioResult withExclude =
        CamelExcludePatternsScenarioRunner.runComparisonScenario("process*,to*", true, messageCount);

    printScenario("without_exclude_patterns", null, messageCount, withoutExclude);
    printScenario("with_exclude_patterns", "process*,to*", messageCount, withExclude);

    System.out.println("=== difference ===");
    long reducedSpanCount = withoutExclude.spanCount() - withExclude.spanCount();
    double reducedPercent = withoutExclude.spanCount() == 0
        ? 0.0
        : (reducedSpanCount * 100.0) / withoutExclude.spanCount();
    System.out.println("message count                           : " + messageCount);
    System.out.println("trace count without exclude             : " + withoutExclude.traceIds().size());
    System.out.println("trace count with exclude                : " + withExclude.traceIds().size());
    System.out.println("span count without exclude              : " + withoutExclude.spanCount());
    System.out.println("span count with exclude                 : " + withExclude.spanCount());
    System.out.println("reduced span count                      : " + reducedSpanCount);
    System.out.printf("reduced span percent                    : %.2f%%%n", reducedPercent);
    printSpanDelta("processValidate", withoutExclude, withExclude);
    printSpanDelta("processEnrich", withoutExclude, withExclude);
    printSpanDelta("toInternal", withoutExclude, withExclude);
    printSpanDelta("processAsync", withoutExclude, withExclude);
    printSpanDelta("processFinalize", withoutExclude, withExclude);
    printSpanDelta("async-custom-span", withoutExclude, withExclude);
  }

  private static void printScenario(
      String label,
      String excludePatterns,
      int messageCount,
      CamelExcludePatternsScenarioRunner.ScenarioResult result) {
    System.out.println("=== scenario: " + label + " ===");
    System.out.println("exclude-patterns: " + (excludePatterns == null ? "<none>" : excludePatterns));
    System.out.println("message count   : " + messageCount);
    System.out.println("trace count     : " + result.traceIds().size());
    System.out.println("span count      : " + result.spanCount());
    System.out.println("span names      : " + new TreeSet<>(result.spanNames()));
  }

  private static void printSpanDelta(
      String spanName,
      CamelExcludePatternsScenarioRunner.ScenarioResult withoutExclude,
      CamelExcludePatternsScenarioRunner.ScenarioResult withExclude) {
    System.out.printf("%-38s: %d%n", spanName + " count without exclude",
        withoutExclude.spanNameCount(spanName));
    System.out.printf("%-38s: %d%n", spanName + " count with exclude",
        withExclude.spanNameCount(spanName));
  }
}
