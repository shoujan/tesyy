# DarkCore Client - Camera/Microphone Test Build

This is based on the previously working Android project.

## What changed
- Automatic connection to `https://remote-test.shoujansapkota.com.np` on launch.
- Camera, microphone and notification permissions are requested together when missing.
- Visible foreground service notification.
- Host `request_camera` starts a camera test stream after Android permissions have been granted.
- Host `stop_camera` stops it.
- Host `request_microphone` starts a microphone test stream after Android permissions have been granted.
- Host `stop_microphone` stops it.
- Camera frames are sent as JPEG/base64 WebSocket messages at about 5 fps.
- Microphone audio is sent as 16 kHz mono 16-bit PCM/base64 chunks.

## Important Android behavior
Android's camera/microphone permission and foreground-service rules remain enforced. The app does not bypass permission dialogs or hide active sensor use. A visible notification changes to indicate camera/microphone sharing is active.

## Build
GitHub Actions builds `app-debug.apk` and uploads it as `darkcore-client-camera-mic-debug-apk`.

## Protocol
Camera frame:
`{"type":"camera_frame","mime":"image/jpeg","data":"..."}`

Microphone chunk:
`{"type":"audio_chunk","sample_rate":16000,"channels":1,"encoding":"pcm_s16le","data":"..."}`

The Host must decode these messages to display/play them.

## Screen sharing test mode

This version adds Android MediaProjection screen sharing. The user must approve the Android system screen-capture dialog. After approval, the active projection session is maintained by the foreground service until it is stopped or Android ends it.

Host control messages:
- `request_screen`
- `stop_screen`

Client media message:
- `screen_frame` with JPEG data in Base64
