# DarkCore Android Client — Background MVP

This version is configured for:

`https://remote-test.shoujansapkota.com.np`

It connects to:

`wss://remote-test.shoujansapkota.com.np/ws`

## Background behavior

The connection is maintained by an Android Foreground Service.

It:
- shows a persistent DarkCore notification
- uses `START_STICKY` so Android can recreate the service after some process deaths
- uses OkHttp WebSocket pinging
- reconnects after network failures with exponential backoff up to 60 seconds
- handles disconnect/reconnect status

### Important Android limitation

No normal Android application can guarantee "always alive forever". Android, the user, OEM battery management, force-stop, reboot, system policy, or loss of internet can stop it. The app therefore uses the supported Foreground Service mechanism and a visible notification instead of trying to bypass Android restrictions.

For testing, allow notifications and, if your phone manufacturer provides battery optimization controls, set DarkCore to the normal "Unrestricted"/"Don't optimize" option. Do not disable Android security features.

## Build online

Upload this project to GitHub, then use:

Actions → Build DarkCore Android APK → Run workflow

Download the `darkcore-client-debug-apk` artifact.

## Test

1. Keep the Windows DarkCore Host running.
2. Keep `cloudflared tunnel ... run` running.
3. Verify the public `/health` endpoint.
4. Install this APK.
5. Use mobile data on Android.
6. Press CONNECT.
7. Lock the phone.
8. Confirm the persistent DarkCore notification remains.
9. On the Host, check that the client remains connected.

This is a connectivity/background-service MVP. It does not include hidden operation, keylogging, credential capture, security bypass, or stealth functionality.
