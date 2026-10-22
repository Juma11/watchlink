# WatchLink

Bluetooth bridge between a Samsung phone and the HainoTeko FG-3 Android watch.

- Caller name and number on the watch, with Answer, Reject, Silence, Hang up
- Every phone notification mirrored to the watch, dismiss from either side
- Contacts synced from the phone, tap to call (the phone places the call)
- Battery tools on the watch: Wi-Fi toggle, dim screen, battery saver, airplane shortcut
- On-watch log viewer, since the FG-3 has no USB data

Call audio stays on the phone or earbuds paired to the phone. The FG-3 ROM ships with
`profile_supported_hfpclient = false`, so the watch cannot act as a hands-free kit without root.

## Modules

| Module | Device | Min SDK |
|---|---|---|
| `phone` | Samsung | 28 |
| `watch` | FG-3 (Android 8.1) | 26 |

## Link

Bluetooth Classic RFCOMM, UUID `7c9a1e2e-5b7d-4f0e-9c3a-3f6b2d8e1a01`.
The watch listens. The phone connects and reconnects with backoff (5s to 60s).
Messages are one JSON object per line.

| From | `t` | Fields |
|---|---|---|
| phone | `hello` | `v` |
| phone | `call` | `state` (ringing, offhook, idle), `num`, `name` |
| phone | `notif` | `key`, `app`, `title`, `text`, `time`, `sync` |
| phone | `notif_rm` | `key` |
| phone | `contacts` | `list` of `{n, p}` |
| both | `ping` / `pong` | `bat` |
| watch | `hello` | `bat`, `model` |
| watch | `cmd` | `a` (answer, reject, hangup, silence, dial), `num` |
| watch | `notif_dismiss` | `key` |
| watch | `get_contacts` | |

## Build

GitHub Actions builds both APKs on every push to `main` and publishes them to the
`latest` release. Both are signed with the fixed key in `keystore/`, so new builds
install over old ones.

## Setup

1. Pair the watch and phone in Bluetooth settings.
2. Watch: install `WatchLink-Watch.apk`, open it once.
3. Phone: install `WatchLink-Phone.apk`, work through buttons 1 to 5.
4. Samsung: add WatchLink to Never sleeping apps.
