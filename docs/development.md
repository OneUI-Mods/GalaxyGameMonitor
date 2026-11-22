[English](development.en.md) · [利用ガイド](../README.md)

# 開発

Android SDK 37.2とJDK 21で `./gradlew :app:testDebugUnitTest :app:lintRelease` を実行します。署名済みAPKは `.signing/release.properties` に `storeFile`、`storePassword`、`keyAlias`、`keyPassword` を置き、`./gradlew :app:assembleRelease` で生成します。

フックの適用範囲はゲームブースターです。AdrenoではKGSLの `gpubusy` の時間比を使います。XclipseとMaliではSamsungの `gpu_busy`、または対応するGPUデバイスが公開する使用率を使います。

CPUは `/proc/stat` のコア別カウンターを2回読み、稼働時間と全時間の差分を合計します。読めない端末では、Androidのハードウェアサービスが公開するカウンターを試します。最大クロック換算では、周波数別の滞在時間とカーネルのコア性能重みも使います。滞在時間を読めない場合は現在のクロック、換算に必要な値がそろわない場合は稼働時間ベースの値を使います。使用率を計測できない項目はGOSの値を維持します。
