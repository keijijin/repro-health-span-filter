# Camel `excludePatterns` / `propagateContext` 調査レポート

## 概要

このレポートは、Camel OpenTelemetry において `excludePatterns` を使うと trace context が失われる問題を、
このリポジトリ内で再現し、`propagateContext` による解決策を実装し、その効果をテストで実証した結果をまとめたものです。

対象バージョン:

- Java: `21`
- Red Hat build of Apache Camel for Spring Boot archetype 系: `4.14.4.redhat-00010`
- 実際に解決された Camel 実行ライブラリ: `4.14.4.redhat-00008`

## 問題状況

Camel OpenTelemetry で `excludePatterns` を設定すると、対象 Processor の span 自体は作成されなくなります。
しかし既定状態では、その除外 Processor の実行中に OpenTelemetry Context が current context として有効化されない場合があります。

その結果、次のような処理で問題が起きます。

- 除外された Processor の中で `@WithSpan` 相当の custom span を作る
- 除外された Processor の中から別スレッドへ処理を渡し、その先で span を作る

このとき、custom span が元の trace にぶら下がらず、別 trace として分岐して見えることがあります。

## 再現方法

再現コードは `src/test/java/com/example/repro/CamelExcludePatternsPropagationTest.java` にあります。

再現条件:

- `excludePatterns` に `process*` を設定
- `processAsync` Processor を trace 対象から除外
- その Processor 内で `Context.current().wrap(...)` した別スレッド処理を実行
- 別スレッド内で `async-custom-span` を生成

ポイントとなるコード:

```java
Runnable task = Context.current().wrap(() -> {
    Span span = tracer.spanBuilder("async-custom-span")
        .setParent(Context.current())
        .startSpan();
    try (Scope ignored = span.makeCurrent()) {
        // noop
    } finally {
        span.end();
    }
});
```

この処理は、元の Camel trace context が current context に存在していることを前提にしています。

## 解決実装

解決実装は `src/main/java/com/example/repro/CamelOpenTelemetrySupport.java` と
`src/main/java/com/example/repro/CamelOpenTelemetryConfiguration.java` に追加しました。

実装内容:

- `excludePatterns` を維持する
- `OpenTelemetryTracingStrategy` を明示生成する
- `setPropagateContext(true)` を設定する
- `Tracer` / `ContextPropagators` は optional 扱いにする
- `CamelContextConfiguration` で Camel 起動直前に tracer を初期化する

実装コード:

```java
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
```

この実装により、除外対象 Processor 自体の span は作らず、Processor 内の current context だけを downstream 処理へ伝搬できます。
また、Spring Boot / Camel の初期化循環を避けつつ、Java Agent 利用時のように `Tracer` bean が存在しない環境でも起動しやすくしています。

## 推奨設定

設定プロパティとして扱える環境であれば、考え方としては次の形です。

```yaml
camel:
  opentelemetry:
    enabled: true
    exclude-patterns:
      - ^(process|to).*
    propagate-context: true
```

ただし、少なくとも一部の Camel Spring Boot starter / Red Hat build では、
`propagate-context` が設定プロパティとして明示公開されていない版があります。

そのため、このサンプルでは YAML だけに依存せず、Java 実装で `setPropagateContext(true)` を明示しています。
最新実装ではさらに、YAML 配列形式の `exclude-patterns` と、Camel 初期化順序の制御も取り込んでいます。

## 実証結果

検証は `mvn test` で実施しました。

対象テスト:

- `excludedProcessorWithoutPropagateContextSplitsTraceAcrossAsyncTask`
- `propagateContextKeepsSingleTraceWhileExcludingProcessorSpan`

期待結果と実結果:

1. `propagateContext=false`
   - 期待: trace が 2 本に分岐する
   - 実結果: `traceIds().size() == 2`
   - 実結果: `processAsync` span は作成されない

2. `propagateContext=true`
   - 期待: custom span が元の trace にぶら下がる
   - 実結果: `traceIds().size() == 1`
   - 実結果: `processAsync` span は作成されない
   - 実結果: 後続の `afterDelay` span は作成される

つまり、`excludePatterns` を維持したまま `propagateContext` を有効化することで、
span 数の増加を抑えつつ trace 分岐問題を解消できることを確認しました。

また、`exclude-patterns` を指定した場合としない場合の span 差分を確認するため、
`scripts/prove_exclude_patterns_difference.sh` を追加しました。

このスクリプトは以下を比較表示します。

- `exclude-patterns` なし
- `exclude-patterns=process*,to*`

比較ポイント:

- `processAsync` span の有無
- trace 数
- span 総数
- span 名一覧

## 実行手順

```bash
cd repro-health-span-filter
mvn test
```

必要なら特定テストのみ実行できます。

```bash
mvn -Dtest=CamelExcludePatternsPropagationTest test
```

差分比較スクリプト:

```bash
./scripts/prove_exclude_patterns_difference.sh
```

メッセージ数を増やす場合:

```bash
MESSAGE_COUNT=100 ./scripts/prove_exclude_patterns_difference.sh
```

## Community `camel-opentelemetry2-starter` の OpenShift 実証

コミュニティ版 `camel-opentelemetry2-starter` については、別モジュール
`community-opentelemetry2-springboot-check` を用いて OpenShift 実証も行いました。

主なファイル:

- `community-opentelemetry2-springboot-check/src/main/resources/application.yml`
- `community-opentelemetry2-springboot-check/openshift/stack.yaml`
- `community-opentelemetry2-springboot-check/scripts/prove_opentelemetry2_springboot_openshift.sh`

設定は文字列形式で `exclude-patterns` を指定しています。

```yaml
camel:
  opentelemetry2:
    enabled: true
    trace-processors: true
    exclude-patterns: "process*,to*"
```

OpenShift 実証スクリプト:

```bash
cd community-opentelemetry2-springboot-check
./scripts/prove_opentelemetry2_springboot_openshift.sh
```

実測結果:

- `async-custom-span count`: `10`
- `processValidate-process cnt`: `0`
- `processAsync-process cnt`: `0`
- `toInternal-to count`: `0`

解釈:

- custom async span は collector まで到達しており、span propagation は維持されている
- 一方で不要な processor / to span は出力されておらず、出力抑制も成立している

したがって、少なくとも今回の OpenShift 実証条件では、community 版 `camel-opentelemetry2-starter` でも
**span をプロパゲーションしつつ、不要な出力を抑える**動作を確認できました。

## Red Hat build `camel-opentelemetry2-starter` の実装・実証

Red Hat ビルド版については、別プロジェクト `redhat-opentelemetry2-springboot-check` を追加し、
`camel-opentelemetry2-starter` を使ったローカル検証と OpenShift 実証を行いました。

主なファイル:

- `redhat-opentelemetry2-springboot-check/pom.xml`
- `redhat-opentelemetry2-springboot-check/src/main/resources/application.yml`
- `redhat-opentelemetry2-springboot-check/src/main/java/com/example/redhatspring/RedHatRouteConfiguration.java`
- `redhat-opentelemetry2-springboot-check/src/test/java/com/example/redhatspring/RedHatOpenTelemetry2SpringBootTest.java`
- `redhat-opentelemetry2-springboot-check/scripts/prove_redhat_opentelemetry2_springboot_openshift.sh`
- `redhat-opentelemetry2-springboot-check/scripts/prove_exclude_patterns_difference_openshift.sh`

設定例:

```yaml
camel:
  main:
    run-controller: true
  opentelemetry2:
    enabled: true
    trace-processors: true
    exclude-patterns: "process*,to*"
```

### ローカル検証

```bash
cd redhat-opentelemetry2-springboot-check
mvn test
```

確認できた内容:

- trace は 1 本に保たれる
- `async-custom-span` は残る
- `processValidate-process` / `processAsync-process` / `toInternal-to` は出ない

### OpenShift 実証

実証スクリプト:

```bash
cd redhat-opentelemetry2-springboot-check
./scripts/prove_redhat_opentelemetry2_springboot_openshift.sh
```

実測結果:

- `async-custom-span count`: `10`
- `processValidate-process cnt`: `0`
- `processAsync-process cnt`: `0`
- `toInternal-to count`: `0`

解釈:

- custom async span は collector まで到達しており、span propagation は維持されている
- 一方で不要な processor / to span は出力されておらず、出力抑制も成立している

### `exclude-patterns` なしとの比較

さらに、`exclude-patterns` を実質無効化したケースと、`exclude-patterns: "process*,to*"` を有効にしたケースを
OpenShift 上で比較するスクリプトも追加しました。

```bash
cd redhat-opentelemetry2-springboot-check
./scripts/prove_exclude_patterns_difference_openshift.sh
```

実測結果:

- `no exclude processValidate-process`: `18`
- `with exclude processValidate-process`: `0`
- `no exclude processAsync-process`: `18`
- `with exclude processAsync-process`: `0`
- `no exclude toInternal-to`: `18`
- `with exclude toInternal-to`: `0`
- `no exclude async-custom-span`: `9`
- `with exclude async-custom-span`: `9`

解釈:

- `exclude-patterns` を指定しない場合は processor/to span が継続的に出力される
- `exclude-patterns` を指定すると、それらの span は 0 まで抑制される
- その一方で `async-custom-span` は維持されており、span propagation は失われない

したがって、少なくとも今回の Red Hat ビルド版 `camel-opentelemetry2-starter` の検証範囲では、
**span の伝播とログメッセージ抑制を同時に実現できる**ことを確認できました。

## 注意点

- `trace-processors=true` は問題回避に見える場合がありますが、Processor span を大量に増やす副作用があります。
- 最新実装では `application.yml` の YAML 配列形式でも `exclude-patterns` を受け取れますが、最終的には Camel へカンマ区切り文字列として渡しています。
- `^(process|to).*` のような正規表現でも扱えますが、Camel のパターンマッチはワイルドカードも解釈するため、適用範囲は実際の route / processor id に合わせて確認が必要です。
- `propagate-context` が設定ファイルだけで効くかどうかは、利用している starter 実装に依存します。
- Java Agent を使う環境では `Tracer` bean が存在しない場合があるため、起動時には tracer 依存を必須にしない実装が安全です。
- このサンプルの OpenShift / Java Agent 検証は Actuator span 除去の別論点であり、本レポートの Camel `propagateContext` 検証とは切り分けて読む必要があります。
