# DarkCore Android AUTO-RECONNECT v5

Test build focused on reliable reconnection after long network outages.

## Behavior
- Persists the enabled connection state and Cloudflare WSS endpoint.
- Uses Android ConnectivityManager network callbacks for immediate reconnect attempts when connectivity returns.
- Uses a 60-second reconnect watchdog while the connection is enabled, so reconnect attempts continue even if a network callback is missed.
- Exponential reconnect backoff reaches a 60-second maximum and continues indefinitely while enabled.
- If Android recreates the foreground service, `onCreate()` restores the saved connection and reconnects without requiring the Activity to be opened.
- Previously requested camera/microphone streams remain desired and can auto-resume after a successful reconnect when Auto Resume Media is enabled and permissions are already granted.

## Cloudflare endpoint
`wss://remote-test.shoujansapkota.com.np/ws`

## Important Android limitation
This does not bypass Android force-stop, denied permissions, OEM battery-killing policies, or a user disabling the foreground service. Camera/microphone access still requires Android runtime permission.
