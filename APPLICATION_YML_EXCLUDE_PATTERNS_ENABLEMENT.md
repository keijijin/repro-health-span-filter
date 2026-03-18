# `application.yml` の `camel.opentelemetry.exclude-patterns` を有効化するために行ったこと

## 背景

このサンプルでは、`application.yml` に以下のような設定を書いて、Camel OpenTelemetry の span 出力抑制を設定駆動で行えるようにしています。

```yaml
camel:
  opentelemetry:
    enabled: true
    exclude-patterns:
      - ^(process|to).*
    propagate-context: true
```

ただし、利用している Red Hat build of Apache Camel for Spring Boot / starter の組み合わせでは、
`exclude-patterns` と `propagate-context` をそのまま `application.yml` だけで確実に反映できるとは限りません。

そのため、このリポジトリでは **設定値をアプリ側で明示的に読み取り、`OpenTelemetryTracer` に適用する実装** を追加しました。

## 実施内容

### 1. `application.yml` に設定項目を追加

設定ファイルに `camel.opentelemetry.*` を追加しました。

対象ファイル:

- `src/main/resources/application.yml`

設定内容:

```yaml
camel:
  opentelemetry:
    enabled: true
    exclude-patterns:
      - ^(process|to).*
    propagate-context: true
```

この設定の意図は次のとおりです。

- `enabled`
  - Camel OpenTelemetry を有効化する
- `exclude-patterns`
  - `^(process|to).*` に一致する Processor span を出力対象から外す
- `propagate-context`
  - 除外された Processor 内でも current context を downstream に伝搬する

### 2. `ConfigurationProperties` を追加

`application.yml` の値を Java 側へ bind するため、専用の properties クラスを追加しました。

対象ファイル:

- `src/main/java/com/example/repro/CamelOpenTelemetryProperties.java`

このクラスで扱っている項目:

- `enabled`
- `excludePatterns`
- `propagateContext`

役割:

- `camel.opentelemetry.exclude-patterns` を `excludePatterns` として取得する
- YAML 配列で指定された値を `List<String>` として受け取る
- `camel.opentelemetry.propagate-context` を `propagateContext` として取得する

### 3. `OpenTelemetryTracer` の自動構成を追加

starter 実装に全面依存せず、アプリ側で `OpenTelemetryTracer` を組み立てるための configuration クラスを追加しました。

対象ファイル:

- `src/main/java/com/example/repro/CamelOpenTelemetryConfiguration.java`

このクラスで行っていること:

- `@EnableConfigurationProperties(CamelOpenTelemetryProperties.class)` で設定を有効化
- `camel.opentelemetry.enabled=true` のときだけ有効化
- `OpenTelemetryTracer` bean が未登録の場合に限り、自前の tracer bean を登録
- `application.yml` から読んだ `exclude-patterns` と `propagate-context` を実際の tracer に適用

これにより、設定値が確実に Camel 側へ流れるようにしています。

### 4. `OpenTelemetryTracer` 組み立てロジックを共通化

Tracer の組み立ては共通化して、テストとアプリ本体の両方から同じロジックを使えるようにしました。

対象ファイル:

- `src/main/java/com/example/repro/CamelOpenTelemetrySupport.java`

この中で行っている処理:

- `OpenTelemetryTracer` を生成
- `setTracer(tracer)` を設定
- `setContextPropagators(contextPropagators)` を設定
- `setExcludePatterns(excludePatterns)` を設定
- `List<String>` を Camel が受け取れるカンマ区切り文字列へ変換
- `OpenTelemetryTracingStrategy` を生成
- `setPropagateContext(propagateContext)` を設定
- `camelTracer.init(camelContext)` を実行

ポイントは、`exclude-patterns` だけでなく **`propagateContext` も同時に適用している**ことです。

これにより、

- 不要な Processor span は減らす
- custom span / downstream span の trace continuity は維持する

という動作を両立しています。

## なぜここまで必要だったか

理由は次の 2 点です。

### 1. starter だけでは `propagate-context` の扱いが版依存

`OpenTelemetryTracingStrategy` 自体には `setPropagateContext(boolean)` がありますが、
Spring Boot starter 側でこの設定がプロパティとして十分公開されていない版があります。

そのため、`application.yml` に書いただけでは期待どおり動く保証が弱く、
アプリ側で明示的に bean を組み立てる必要がありました。

### 2. `exclude-patterns` だけでは trace context 問題が残る

`exclude-patterns` を設定すると、対象 Processor の span は消せます。
しかし、既定状態では除外 Processor 内で OpenTelemetry context が current context に乗らず、
custom span が別 trace に分岐することがあります。

そのため、`exclude-patterns` を有効化するだけでなく、
**`propagate-context` も同時に有効化する実装** が必要でした。

## 実際に有効になっていることの確認方法

### 1. 単体テスト

対象ファイル:

- `src/test/java/com/example/repro/CamelExcludePatternsPropagationTest.java`

確認している内容:

- `exclude-patterns` 有効時に `processAsync` span が出ない
- `propagate-context=true` で custom span が同一 trace に残る

実行コマンド:

```bash
mvn test
```

### 2. 差分比較スクリプト

対象ファイル:

- `scripts/prove_exclude_patterns_difference.sh`

このスクリプトでは、`exclude-patterns` なし / ありで span 数を比較できます。

実行例:

```bash
./scripts/prove_exclude_patterns_difference.sh
```

例として 100 メッセージ処理時には、次のような差を確認できます。

- `exclude-patterns` なし: `1000 span`
- `exclude-patterns` あり: `500 span`
- 削減数: `500 span`
- 削減率: `50%`

## まとめ

`application.yml` の `camel.opentelemetry.exclude-patterns` を有効に使うために、このリポジトリでは以下を追加しました。

1. `application.yml` に `camel.opentelemetry.*` 設定を追加
2. `ConfigurationProperties` で設定を bind
3. `OpenTelemetryTracer` をアプリ側で明示構築する configuration を追加
4. `exclude-patterns` と `propagate-context` を同時適用するサポートクラスを追加
5. テストと比較スクリプトで有効性を実証

結果として、

- `application.yml` で span 抑制ルールを指定できる
- 不要な Processor span を大幅に減らせる
- trace のつながりは維持できる

状態になっています。
