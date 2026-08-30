# Scout Alarm Console

A single-page Web Bluetooth console for the Knog Scout bike alarm: arm, disarm,
and watch alarm state and battery live from a phone.

Open **https://yannickpoffet.github.io/knog-scout-console/** in Chrome or Edge on
Android, tap **Connect**, and pick the Scout.

## How it works

The Scout exposes a vendor GATT service whose characteristics are named by their
own `0x2901` Characteristic User Description descriptors:

| Characteristic | Name | Use |
| --- | --- | --- |
| `00000001-feed-0bac-5241-d8bda6932a2f` | Control Point | write `01` to arm, `02` to disarm |
| `00000002-feed-0bac-5241-d8bda6932a2f` | Alarm Active | notifies the arm state |
| `0x2A19` (Battery Service) | Battery Level | percent, pushed every 10s |

Alarm Active values: `0` off, `1` arming, `2` armed, `3` ringing. Note the
opcodes are *not* the state values — writing `02` while already disarmed is a
no-op, not an arm.

## Requirements

Web Bluetooth needs a secure context, so this must be served over HTTPS —
`file://` will not work. Chrome or Edge on Android only; Firefox and iOS have no
Web Bluetooth. Android also needs Location enabled for BLE scanning, and the
Scout accepts one connection at a time, so disconnect the Knog app first.

Unofficial, not affiliated with Knog.
