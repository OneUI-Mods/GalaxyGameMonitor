[日本語](README.md) · [Development](docs/development.en.md)

# Galaxy Game Monitor

Show measured CPU and GPU usage and per-game memory in Samsung Game Booster, and configure GPU performance.

[Download the latest release](https://github.com/OneUI-Mods/GalaxyGameMonitor/releases/latest)

## Install

1. Install the signed APK from the release page on a Galaxy running Android 17 or later.
2. Enable the module in Vector or another framework that supports libxposed API 102.
3. Select the scope for each feature you want to use. The module first loads in each scoped app the next time its process starts.

| Feature | Vector scope |
| --- | --- |
| System monitor and in-game settings | **Game Booster** |
| Read and bypass the GOS GPU cap | **Game Optimizing Service** |
| SDHMS GPU temperature control and requested ceiling | **Samsung Device Health Manager** (Galaxy S26 Ultra on BZI9) |

- Vector can hot-update apps where the module is already loaded.
- To restart Game Booster on its own, select **Reload Game Booster** in Galaxy Game Monitor (requires root).

## Open settings

| Where | How |
| --- | --- |
| Galaxy Game Monitor app | Edit the monitor layout or tap **GPU performance** |
| In a game | **Game Booster settings → System monitor** or **GPU performance** |
| Full Game Booster settings | Tap **Monitor** at the top |

## System monitor

- Choose which metrics to show. Available metrics include FPS, CPU and GPU usage, GPU clock, system and game memory, and battery level and temperature. GPU support includes Adreno, Xclipse, and Mali. Available readings vary by device.
- Reorder items and choose 1–8 items per row (four by default). CPU and GPU usage can also be shown relative to peak clock speed.
- Turn off **Show in screenshots and screen recordings** to keep the monitor visible on the device and hide it from captures.
- Game memory (PSS, in GiB) requires root access for Galaxy Game Monitor.

## GPU performance

| Setting or reading | What it does |
| --- | --- |
| **Bypass GOS GPU clock cap** | Filters GOS GPU ceiling requests during games. |
| **Keep SDHMS GPU temperature control in games** | Turns on when you enable GOS bypass. You can turn it off manually. Restoring the GOS cap also turns this setting off. |
| **GPU ceilings** | Shows the GOS and SDHMS requests and the effective GPU ceiling separately. |
| **GMU target FPS** | On supported Adreno devices, choose System default, 60 FPS, or 120 FPS. Changing the target requires root access. |

- SDHMS renews its GPU request while it holds the ceiling. The request expires 30 seconds after the last renewal.
- With root access, Galaxy Game Monitor checks for and clears ceilings left by previous SDHMS processes when SDHMS connects.
