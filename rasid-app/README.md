# رصيد (Rasid) — mobile-credit POS for MTN and Syriatel

An Android app for shops that resell mobile credit in Syria. Its feature set was mapped from the
BRES POS app (v9.0.0 Beta8) and written from scratch. No code or assets were copied.

## Features

- **Transfers over USSD** on the correct SIM:
  - MTN: prepaid, postpaid, cash and DSL bills
  - Syriatel: prepaid, postpaid and cash
- **Two ways to send**:
  - The Android USSD API (default). The carrier's reply is read and classified as success, failure, pending or needs-review.
  - Through the dialer. An optional accessibility service reads the reply, and it only looks at the screen while a request from the app is in flight.
- **Stock per channel**:
  - Current balance, minimum-balance alert and cost per 10,000 units
  - Top-ups and manual corrections
  - USSD balance and last-transfer inquiries, with "use this as the current balance"
- **Price categories** per carrier (denomination → selling price), with profit on every sale.
- **Customers and debts**:
  - Debt sales and payments
  - Account settlement that zeroes the balance while keeping the history
  - A running-balance statement you can share (WhatsApp, SMS…)
  - Import from contacts
- **Shifts**:
  - Sales, profit and debt totals, plus profit per channel
  - Start a new shift; browse any shift in the history
  - Delete a shift's log behind a password. Debt sales are kept.
- **History** filtered by shift, date range and carrier. A transaction's status can be changed behind a password, and stock is corrected to match.
- **JSON backup and restore**. PINs and the password are never written to the backup file.

Not included: the original app's licensing and trial system, and pairing with the vendor's BRES
hardware.

## Build

The APK is built by the `Rasid Android build` GitHub Actions workflow
(`.github/workflows/rasid-android.yml`). Download `rasid-debug-apk` from the run's artifacts.

To build locally with the Android SDK installed:

```bash
cd rasid-app
./gradlew testDebugUnitTest assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## First run

1. Allow **phone calls** and **phone state** when asked. They are needed to send USSD and pick the SIM.
2. **Settings → MTN**: enter the PIN, cash PIN, DSL code and DSL governorate.
3. **Settings → Syriatel**: enter the distributor code (SSC) and secret code (SPC).
4. If the SIM is not detected automatically, set the slot order in Settings.
5. **Balances**: set each channel's current balance, minimum and cost per 10,000 units.

The default password for protected actions is `123456`. Change it in Settings.

## USSD formats

| Operation          | Code                                         |
| ------------------ | -------------------------------------------- |
| MTN prepaid        | `*150*PIN*NUMBER*AMOUNT#`                    |
| MTN postpaid       | `*154*PIN*NUMBER*AMOUNT#`                    |
| MTN cash           | `*151*1*CASHPIN*NUMBER*AMOUNT#`              |
| MTN DSL            | `*160*DSLCODE*963AREALINE*AMOUNT#`           |
| Syriatel prepaid   | `*150*1*SPC*1*AMOUNT*NUMBER*NUMBER#`         |
| Syriatel postpaid  | `*150*1*SPC*5*AMOUNT*NUMBER*NUMBER#`         |
| Syriatel cash      | `*150*10*SPC*1*AMOUNT*NUMBER*NUMBER#`        |
| MTN balance        | prepaid `*155*1#`, cash `*151*3#`            |
| Syriatel balance   | `*150*2*SSC*SPC*1#` / `*5#`, cash `*150*12*SSC*SPC*3#` |
| Last transfers     | MTN `*155*3#`, Syriatel `*150*6*SSC*SPC*0#`  |

These come from the original app. Check them against your own distributor account before relying
on them, because carriers change codes.
