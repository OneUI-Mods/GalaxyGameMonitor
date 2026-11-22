[English](development.en.md) · [利用ガイド](../README.md)

# 開発

Android SDK 37.2とJDK 21で `./gradlew :app:testDebugUnitTest :app:lintRelease` を実行します。署名済みAPKは `.signing/release.properties` に `storeFile`、`storePassword`、`keyAlias`、`keyPassword` を置き、`./gradlew :app:assembleRelease` で生成します。

フックの適用範囲はゲームブースターです。システムモニター向けのGOS応答にあるGPU使用率を、KGSLの `gpubusy` が返す稼働時間と測定時間の比率で更新します。読み取れない場合はGOSの値を使います。CPUの換算をオンにした場合は、コアごとの稼働時間、周波数別の滞在時間、カーネルのコア性能重みを使います。滞在時間が取得できないときは取得時点のクロックを使います。
