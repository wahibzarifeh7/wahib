# Glass Finder — developer / maintainer notes

This file is for building and maintaining the app itself. It is **never shipped to a customer** — `installer/build.mjs` only bundles `README.md` and `README.ar.md` (the customer-facing guides, English and Arabic) into the installed copy, never this one. Keep customer-facing wording out of here and dev-facing wording out of the README files. If you add or reword a feature in `README.md`, mirror the change in `README.ar.md` too (see the exact-on-screen-label discipline note below) — they should never drift out of parity.

**Keeping `README.ar.md` in sync:** every UI element it names (menu items, button labels, dialog titles) should match the literal Arabic string in `public/i18n.js`'s `ar` block, not a fresh translation of the English README — a customer reading the Arabic guide should see the exact word on their screen. When a new feature's English README bullet is added, look up (or add) its Arabic UI strings in `i18n.js` first, then write the Arabic README bullet using those exact strings.

## Activation codes

Each install is locked to the one PC it's activated on — copying the app folder (or handing someone the installer) to a different computer will not run there. First time it's opened, it shows that PC's **Device ID** and asks for an **activation code**. Generate one (it only works for that one Device ID) with:

```
node installer/activate.mjs <DEVICE-ID>
```

using `installer/keys/private.pem` — the one file that can authorize a PC. **Back this file up somewhere safe** (outside this project folder too); if it's ever lost, no PC can be activated or re-activated again, including ones already running fine today (those keep working, they just could never move or be reactivated). Never send this file or share it — it's what stops the app being copied to another PC without your say-so.

**Surviving a Windows reinstall:** the Device ID is read from the motherboard itself (its firmware serial), not from anything Windows creates — so reinstalling Windows on the same PC keeps the same Device ID, and the app just keeps working, no new code needed. If a PC is ever genuinely different (or the motherboard serial isn't readable for some reason), it'll show "locked to a different PC" instead: paste a new code there and it re-keys itself while keeping the existing username and password — no need to choose a new password. Re-key with `renew(code)` logic in `lib/license.mjs`; it deliberately does not log the caller in, so a code alone can't bypass the password.

**License backup:** `⋯ > Download activation backup` in the app saves the whole `license.json` (including the password hash) so a customer can restore it themselves if the file is ever lost or corrupted, without needing a new code — as long as it's restored on the same PC.

License state lives in `license.json` next to (not inside) `data/`, deliberately excluded from backup/restore and from the installer seed — every install must activate on its own.

## PC-lock is bypassable — don't oversell it

The Ed25519 signature check on an activation code is cryptographically sound (nobody can forge a code for a device they don't have from you), but the *enforcement* is just plain, readable `.mjs` text with no code signing or integrity check — anyone with basic JS knowledge and access to the installed folder could open `server.mjs` and delete the `if (license.status() !== 'ok') throw ...` line. This is true of any offline license check running on hardware the "attacker" fully controls; it deters casual copying, not a determined attempt. If this app is sold more widely and cracking becomes a real concern, options discussed (not yet built): package as a single compiled executable (Node's built-in SEA feature) instead of shipping readable source, build-time-only JS obfuscation, a self-integrity check, and sprinkling the license check in multiple places. All offline-only, no phone-home (a deliberate constraint) — none of it is unbreakable, just raises the effort required.

## Two independent copies of the data

There are two separate live copies of the data during development:

1. Source folder (`D:\glass compatibility\data\db.json`) — the one this repo's `Start Glass Finder.bat` runs against.
2. The installed copy at `%LOCALAPPDATA%\Programs\Glass Finder\data\db.json`, launched from the Setup.exe-created shortcuts.

These do **not** sync automatically — editing one never updates the other. Before rebuilding the installer, sync the source folder's `data/db.json` and `data/images` **from** whichever copy has the more current/correct data (normally the installed copy, since that's the one in daily real use) — otherwise a rebuild ships stale data as the starter list for new installs.

## Running it from source

Double-click **Start Glass Finder.bat**. A window opens with the app; keep the black console window open while you work, close it to stop. It needs [Node.js](https://nodejs.org) on PATH.

**On a phone:** double-click **Start Glass Finder (phone access).bat** instead — same idea as the installed copy's phone-access shortcut, but runs against the source folder's data via system Node instead of the bundled runtime.

## Re-importing the Excel file

Only needed once per customer, already done for the current data. To redo it from scratch (replaces everything):

```
python tools/dump_excel.py "C:\path\to\mobile_phone_models.xlsx"
node tools/build_db.mjs --force
```

The dumped intermediate file (`data/excel_rows.json`) is kept after import — useful for tracing a "typo" back to the original sheet if two models turn out to be mixed into one bad entry (a missing `/` separator in the source text is the usual cause; check `public/parse.js`'s splitting logic against the raw row before assuming it's a parser bug).

## Rebuilding the installer

After changing the code (and syncing the source folder's data per above), rebuild **Glass Finder Setup.exe** with:

```
node installer/build.mjs
```

It stages the current `server.mjs`, `lib/`, `public/`, `README.md` and `README.ar.md` (the customer-facing guides), the current `data/` (as the starter list for a fresh install only), a copy of Node.js, and the icon, then wraps it all with tools already built into Windows (PowerShell's zip, robocopy, and IExpress) — nothing extra to install. Built entirely from OS-native tools, deliberately no third-party installer software.

**Testing before shipping:** always test a change in a scratch copy of the data (a fresh `GLASS_DATA_DIR`/`GLASS_LICENSE_DIR`-pointed server instance) before running the real installer over a customer's live install. When reinstalling over a real install, stop the running app first, back up `license.json` and `data/db.json`, run the installer, then diff both against the backup to confirm they came through untouched.

## The logo

`installer/assets/source-logo.webp` is the original artwork. `installer/assets/make-icon.py` (needs Python + Pillow) crops it into the app icon and the full lockup image, and builds `icon.ico`:

```
python installer/assets/make-icon.py installer/assets/source-logo.webp
```

Run it again after swapping in a new logo image, then rebuild the installer.
