[日本語](README.md) · [Development](docs/development.en.md)

# Galaxy Game Monitor

Show CPU and GPU usage measured by the kernel in Game Booster’s system monitor. GPU support includes Adreno and the Xclipse and Mali GPUs used on Exynos devices.

On devices that expose the current GPU clock, the overlay shows it in MHz next to GPU usage.

Turn on **Show GPU relative to peak clock** to show an estimate in the `GPU %` field based on driver utilization multiplied by operating frequency divided by the GPU’s peak frequency. It uses per-frequency busy times on supported Adreno devices and the sampled clock elsewhere. When conversion is unavailable, the overlay shows the standard `GPU %`.

Use **System monitor items** to show or hide FPS, CPU usage, GPU usage, GPU clock, system RAM usage, game RAM, battery level, and battery temperature individually. Battery temperature is shown in degrees Celsius to one decimal place.

Game RAM shows the resident proportional set size (PSS) of the foreground game’s processes in GiB. It uses Android’s detailed memory measurement, including graphics memory attributed to the game. Grant root access to Galaxy Game Monitor to enable this metric. It shows “—” when unavailable.

Choose 1 to 8 items per row. The default is 4. Use **Reorder items** to move metrics up or down. During a game, open **Game Booster settings → System monitor** in the side panel to edit the same settings. In the full Game Booster settings screen, tap **Monitor** at the top.

Turn off **Show in screenshots and screen recordings** to exclude the monitor from captured output while it remains visible on the device. The setting is on by default.

Enable the module in Vector or another framework with libxposed API 102 and reload Game Booster. Launch a game and open **System monitor** in Game Booster.

The default CPU display shows the share of time the CPU was busy. Enable **Show CPU load relative to peak clock** for an estimate relative to peak capacity.

After updating the module, choose **Reload Game Booster**, then return to your game. This button requires root access for Galaxy Game Monitor.

Each metric keeps Game Booster’s standard value when the device cannot supply the required telemetry.
