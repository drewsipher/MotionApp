# MotionApp

Android (Kotlin, Jetpack Compose) app that applies a simple motion detection filter to either live camera or a loaded video.

Motion filter: invert current frame, then blend with a previous frame (n frames earlier) with adjustable weight.

Features
- Start screen with choices: Camera or Video
- Camera: live view with motion filter, controls for weight, frame gap (n), zoom, swap camera, back
- Video: open a video, play/pause, scrub, adjustable weight and frame gap

Requirements
- Android Studio 2025+, Gradle 8.6, Kotlin 2.0
- Min SDK 26

Getting started
1. Open this folder in Android Studio
2. Let Gradle sync
3. Run on a device

Notes
- Camera uses CameraX ImageAnalysis and a CPU-based YUV->RGB conversion; performance is acceptable for small frames but not optimized.
- Video decoding uses MediaMetadataRetriever to sample frames; this is simple but not as accurate as a real player pipeline.
- You can later replace the CPU pipeline with GPU shaders or OpenGL for better performance.
