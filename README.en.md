[日本語](README.md) · [Development](docs/development.en.md)

# Galaxy Game Monitor

Show CPU and GPU usage measured by the kernel in Game Booster’s system monitor. GPU support includes Adreno and the Xclipse and Mali GPUs used on Exynos devices.

On devices that expose the current GPU clock, the overlay shows it in MHz next to GPU usage.

Use **System monitor items** to show or hide FPS, CPU usage, GPU usage, GPU clock, RAM usage, and battery temperature individually. Battery temperature is shown in degrees Celsius to one decimal place.

Enable the module in Vector or another framework with libxposed API 102 and reload Game Booster. Launch a game and open **System monitor** in Game Booster.

The default CPU display shows the share of time the CPU was busy. Enable **Show CPU load relative to peak clock** for an estimate relative to peak capacity.

After updating the module, choose **Reload Game Booster**, then return to your game. This button requires root access for Galaxy Game Monitor.

Each metric keeps Game Booster’s standard value when the device cannot supply the required telemetry.
