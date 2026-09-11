# Android DLSS Host

Experimental Android port/host for the **DLSS5ForAll** community pipeline.

The project no longer treats a clean-room temporal filter as the target. The current goal is to preserve the original Windows/ReShade/DLSS5 chain as far as technically possible and replace only the pieces that do not exist on Android.

## Current architecture

1. **Android MediaProjection** captures the phone screen.
2. `AndroidFrameBridgeService` publishes the newest RGBA frame into the Winlator container.
3. `AndroidFramePresenter.exe` (source in `bridge/windows/`) reads that frame and presents it through a minimal D3D11 swapchain with a real depth buffer and exactly one Present per accepted frame.
4. The user's own portable DLSS5ForAll package stays beside the presenter, so its **ReShade / DLSS5-Feeder / RenoDX / NGX** chain can attempt to attach to that D3D11 process under Wine/Box64.
5. The app exposes logs and component checks so we can identify the exact point where compatibility stops on a real ARM64 Android device.

The APK intentionally does **not** bundle `DLSS5ForAll.exe`, `nvngx_dlss.dll`, `nvngx_dlssnr.dll`, ReShade, RenoDX, DLSS5-Feeder or other proprietary/third-party runtime binaries. The user imports their own ZIP at runtime.

## Important technical boundary

The upstream DLSS5ForAll frontend is open-source/MIT, but the actual DLSS/Neural Rendering backend uses NVIDIA NGX and requires a compatible NVIDIA RTX GPU/driver. A normal Android phone GPU cannot magically become an RTX GPU through Wine or Box64.

So this project is being validated in layers:

- **Layer A — Android capture:** MediaProjection -> frame bridge.
- **Layer B — Windows compatibility:** frame bridge -> Wine/Box64 -> D3D11 Present.
- **Layer C — ReShade/feed chain:** load the original add-ons against the presenter.
- **Layer D — NVIDIA NGX:** determine whether the final backend can initialize. On ordinary phone hardware this is expected to be the hard boundary unless an RTX-backed/offloaded backend is introduced.

That distinction is deliberate: when the chain fails, the diagnostics should show *where* it failed instead of presenting a visually similar filter as if it were DLSS 5.

## CI

`.github/workflows/winlator-host-build.yml` builds the open-source Windows presenter with MinGW, embeds only that presenter into the patched Winlator APK, verifies that no proprietary DLSS payload is bundled, compiles the Android host and publishes the ARM64 test APK.

An x86_64 Android emulator is not used as an end-to-end gate for the Winlator/Box64 runtime, because that does not reproduce the ARM64 device environment. Runtime validation is performed on a real ARM64 Android device using the in-app diagnostic logs.
