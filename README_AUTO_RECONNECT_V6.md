# DarkCore Android v6

- 48 kHz mono 16-bit PCM microphone capture with 20 ms chunks.
- Uses UNPROCESSED audio source when supported, with MIC fallback.
- Persistent reconnect watchdog and network callback.
- Restores connection after device reboot when the user previously enabled connection.
- Camera/microphone remain subject to Android runtime permissions and while-in-use foreground-service restrictions.
- Screen capture remains subject to MediaProjection user consent.
