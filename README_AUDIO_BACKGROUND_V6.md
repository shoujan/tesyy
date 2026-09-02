# DarkCore Android v6

Audio: 48 kHz mono 16-bit PCM, 20 ms chunks, UNPROCESSED source when supported with MIC fallback.

Background: persistent connection is restored after reboot through BOOT_COMPLETED and the foreground connection service. Android camera/microphone while-in-use restrictions remain enforced; the app cannot silently grant permissions or guarantee camera/mic capture immediately after reboot.
