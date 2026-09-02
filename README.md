# DarkCore Android Camera/Microphone MVP

Upload this project to GitHub and let the included Actions workflow build it.

This version:
- automatically starts the foreground connection service when the app opens
- connects to the existing Cloudflare WSS endpoint
- requests Camera, Microphone and Notification permissions together on first launch when they are missing
- keeps a visible foreground-service notification
- understands Host request messages for camera/microphone and reports status

Important: Android controls access to camera/microphone. This build does not bypass Android permission dialogs or secretly activate sensors. Actual camera/audio streaming should be added only with explicit user authorization and authenticated pairing.
