[日本語](README.md) · [Development](docs/development.en.md)

# Galaxy Game Monitor

Show the GPU usage measured by the Qualcomm driver in Game Booster’s system monitor. If the GPU is busy for 80% of the sample interval, the monitor displays approximately 80%.

Enable the module in Vector or another framework with libxposed API 102 and reload Game Booster. Launch a game and open **System monitor** in Game Booster.

In Galaxy Game Monitor, turn **Show CPU load relative to peak clock** on for clock-adjusted CPU load or off for Game Booster’s standard CPU usage. It is on by default. Changes apply on the next monitor update.

After updating the module, choose **Reload Game Booster**, then return to your game. This button requires root access for Galaxy Game Monitor.

Each metric keeps Game Booster’s standard value when the device cannot supply the required telemetry.
