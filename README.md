# Actuator Span 発出元除去（Java Agent 1.28.0）実装・実証 README

## 目的

Spring Boot Actuator の `/actuator/health` や `/actuator/info` が大量にトレースへ格納される問題に対して、  
**発出元（Java Agent 側）で確実に除去する方法**を、実装と実測結果付きで示します。

## 結論

- `-Dotel.instrumentation.servlet.exclude-patterns=...` は、`1.28.0` では有効性が確認できず、環境差異の影響を受けやすいです。
- 発出元で確実に除去するには、**Java Agent Extension + custom sampler** を使い、`SamplingDecision.DROP` で Actuator span を落とす方法が有効です。

## 実装概要

実装は以下の3点です。

1. **custom sampler 実装**
   - `SpanKind.SERVER` の span を対象
   - `http.target` / `http.route` / `http.url` / span名からパスを判定
   - `/actuator/health` と `/actuator/info` に一致したら `DROP`

2. **SPI 登録**
   - `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider`
   - Provider クラスを登録

3. **起動時に extension を有効化**
   - `-Dotel.javaagent.extensions=/app/actuator-drop-extension.jar`

## 主要ファイル

- `agent-extension/src/main/java/com/example/otel/ActuatorDropSamplerProvider.java`
- `agent-extension/src/main/resources/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider`
- `agent-extension/pom.xml`
- `Dockerfile`
- `scripts/prove_source_exclusion_openshift.sh`
- `openshift/stack.yaml`

## 実証方法（OpenShift）

以下スクリプトで、**Collector 側フィルタなし**の同一条件比較を行います。

- ケース1: extension OFF（baseline）
- ケース2: extension ON（`-Dotel.javaagent.extensions=...`）

```bash
cd repro-health-span-filter
chmod +x scripts/prove_source_exclusion_openshift.sh
./scripts/prove_source_exclusion_openshift.sh
```

ビルド済みイメージを再利用する場合:

```bash
cd repro-health-span-filter
SKIP_BUILD=true ./scripts/prove_source_exclusion_openshift.sh
```

## 実測結果

実行結果（OpenShift）:

- baseline（extension OFF）
  - `health mentions`: `153`
  - `work mentions`: `30`
- source exclusion（extension ON）
  - `health mentions`: `0`
  - `work mentions`: `30`

解釈:

- `/actuator/health` は発出元で除去できている
- `/api/work` は維持され、業務トレースに影響していない

## 検証ログ

- `logs/openshift_baseline_source_off.collector.log`
- `logs/openshift_source_exclusion_on.collector.log`

## 補足（Collector 側除去）

発出元除去が難しい環境では、Collector の `filter` processor で除去可能です。  
サンプルは `otel/collector-filter-health.yaml` を参照してください。

## Camel `excludePatterns` と `propagateContext` の再現

詳細な解説・推奨設定・注意点・実証結果は `CAMEL_PROPAGATE_CONTEXT_REPORT.md` を参照してください。
`application.yml` の `camel.opentelemetry.exclude-patterns` を有効化するために行った実装は
`APPLICATION_YML_EXCLUDE_PATTERNS_ENABLEMENT.md` に整理しています。
また、最新実装では YAML 配列形式の `exclude-patterns` と、Java Agent 環境でも起動できる
Camel tracer 初期化順序の調整を反映しています。

`camel-opentelemetry2` については、以下の別プロジェクトで検証しています。

- `community-opentelemetry2-check`
- `community-opentelemetry2-springboot-check`
- `redhat-opentelemetry2-springboot-check`

特に `redhat-opentelemetry2-springboot-check` では、Red Hat ビルド版 `camel-opentelemetry2-starter` を用いて、
span propagation を維持しながら `exclude-patterns` によって processor/to 出力を抑制できることを、
ローカルテストと OpenShift 実証の両方で確認しています。

Camel 側の別論点として、`excludePatterns` で除外した Processor 内では、既定状態だと OpenTelemetry の `Context.current()` が root になり、別スレッドで起動した custom span が親 trace から分岐することがあります。

今回のサンプルでは次の 2 ケースを `src/test/java/com/example/repro/CamelExcludePatternsPropagationTest.java` で実証しています。

- `propagateContext=false`
  - 除外された Processor span 自体は作成されない
  - ただし別スレッドの custom span は別 trace へ分岐する
- `propagateContext=true`
  - 除外された Processor span は引き続き作成されない
  - custom span は元の Camel trace にぶら下がる

実行コマンド:

```bash
cd repro-health-span-filter
mvn test
```

`exclude-patterns` を指定した場合としない場合の差を比較表示するには、次のスクリプトを実行します。

```bash
cd repro-health-span-filter
./scripts/prove_exclude_patterns_difference.sh
```

確認ポイント:

- `excludedProcessorWithoutPropagateContextSplitsTraceAcrossAsyncTask`
  - trace 数が `2`
- `propagateContextKeepsSingleTraceWhileExcludingProcessorSpan`
  - trace 数が `1`
  - 除外対象 `processAsync` の span は作成されない
