[日本語](README.md) · [Development](docs/development.en.md)

# Galaxy Game Monitor

Show CPU and GPU activity as a percentage of the device’s peak hardware capacity in Game Booster’s system monitor. For example, a GPU that is 90% busy at half its peak clock displays approximately 45%.

Enable the module in Vector or another framework with libxposed API 102 and reload Game Booster. Launch a game and open **System monitor** in Game Booster. To restore the original display, disable the module and reload Game Booster.

Values are clock-based estimates. Each metric keeps its stock value when the device cannot supply the required telemetry.
