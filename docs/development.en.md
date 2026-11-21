[日本語](development.md) · [User guide](../README.en.md)

# Development

Run `./gradlew :app:testDebugUnitTest :app:lintRelease` with Android SDK 37.2 and JDK 21. To build a signed APK, place `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` in `.signing/release.properties` and run `./gradlew :app:assembleRelease`.

The hook is scoped to Game Booster. It adjusts the CPU and GPU samples in the existing GOS response for the system monitor. GPU load is paired with the clock sample from the same interval. CPU estimates combine each core’s active time, frequency residency, and the kernel’s CPU capacity weights. If residency is unavailable, the CPU calculation uses the current clock reading.
