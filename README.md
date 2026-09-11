# Android DLSS-style / NeuralFrame

Clean-room Android temporal upscaler prototype. It captures a selected app with Android MediaProjection, estimates motion, reprojects reconstructed history, applies neighborhood-clamped temporal reconstruction and adaptive sharpening, then presents the result as a touch-through overlay.

The first validation target is the YouTube Android app. This repository does **not** contain NVIDIA DLSS binaries, leaked models, or proprietary NVIDIA weights.

Current branch is experimental and intended for automated CI/device validation before any APK is presented as final.
