[English](development.en.md) · [利用ガイド](../README.md)

# 開発

Android SDK 37.2とJDK 21で `./gradlew :app:testDebugUnitTest :app:lintRelease` を実行します。署名済みAPKは `.signing/release.properties` に `storeFile`、`storePassword`、`keyAlias`、`keyPassword` を置き、`./gradlew :app:assembleRelease` で生成します。

フックの適用範囲はゲームブースターです。GOSが既に返すシステムモニター向け応答のCPU・GPUサンプルだけを補正します。GPUは同じ区間の負荷とクロックを組にして換算します。CPUではコアの稼働時間、周波数別の滞在時間、カーネルのコア性能重みを使います。滞在時間が取得できないときは取得時点のクロックを使います。
