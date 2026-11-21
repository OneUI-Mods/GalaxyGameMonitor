[日本語](README.md) · [Development](docs/development.en.md)

# Galaxy Game Monitor

Show CPU and GPU activity as a percentage of the device’s peak hardware capacity in Game Booster’s system monitor. For example, a GPU that is 90% busy at half its peak clock displays approximately 45%.

Enable the module in Vector or another framework with libxposed API 102 and reload Game Booster. Launch a game and open **System monitor** in Game Booster.

In Galaxy Game Monitor, turn **Show relative to peak clock** on for clock-adjusted load or off for Game Booster’s standard CPU and GPU usage. It is on by default. Changes apply on the next monitor update.

Values are clock-based estimates. Each metric keeps its stock value when the device cannot supply the required telemetry.
