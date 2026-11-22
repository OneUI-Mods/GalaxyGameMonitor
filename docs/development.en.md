[日本語](development.md) · [User guide](../README.en.md)

# Development

Run `./gradlew :app:testDebugUnitTest :app:lintRelease` with Android SDK 37.2 and JDK 21. To build a signed APK, place `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` in `.signing/release.properties` and run `./gradlew :app:assembleRelease`.

The hook is scoped to Game Booster. On Adreno it reads the busy-time ratio from KGSL’s `gpubusy` counters. On Xclipse and Mali it reads Samsung’s `gpu_busy` or the usage reported by the corresponding GPU device.

CPU usage compares two per-core snapshots from `/proc/stat` and totals the deltas in busy time and elapsed time. If the file is unavailable, it tries Android’s hardware service. The optional peak-clock estimate also uses frequency residency and kernel CPU capacity weights. It uses the current clock when residency is unavailable and falls back to busy-time usage if peak normalization cannot be calculated. When no usage sample is available, the hook keeps the corresponding GOS value.
