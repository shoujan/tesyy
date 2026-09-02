# DarkCore Android - Auto Reconnect / Media Resume

This test build keeps the existing Cloudflare WSS endpoint and adds:

- Automatic reconnect with exponential backoff (2s up to 60s).
- Immediate reconnect attempt when Android reports an Internet-capable network is available again.
- Persists the Cloudflare WSS host across service restarts.
- Visible **Auto-reconnect and resume camera/microphone** switch (enabled by default).
- If the user previously started camera and/or microphone sharing and the switch is enabled, those streams resume after a network reconnect, provided the Android runtime permissions are still granted.
- Camera capture has a fallback from `TEMPLATE_PREVIEW` to `TEMPLATE_RECORD` for devices that reject the preview template.

Android runtime permissions are still controlled by Android. The app cannot silently grant CAMERA or RECORD_AUDIO permission.

Endpoint:

`wss://remote-test.shoujansapkota.com.np/ws`

External port: 443 via Cloudflare.
