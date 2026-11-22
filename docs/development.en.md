[日本語](development.md) · [User guide](../README.en.md)

# Development

Run `./gradlew :app:testDebugUnitTest :app:lintRelease` with Android SDK 37.2 and JDK 21. To build a signed APK, place `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` in `.signing/release.properties` and run `./gradlew :app:assembleRelease`.

The hook is scoped to Game Booster. It updates the GPU usage in the system monitor’s GOS response using the ratio of busy time to sample time from KGSL’s `gpubusy` counters. If the counters are unavailable, the hook keeps the GOS value. When enabled, the CPU estimate combines each core’s active time, frequency residency, and the kernel’s CPU capacity weights. If residency is unavailable, the CPU calculation uses the current clock reading.
