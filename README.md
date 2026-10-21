# Glass Finder

*هذا الدليل متوفر بالعربية أيضًا: [README.ar.md](README.ar.md).*

Type a phone model, see which box holds its screen protector.

## Installing it

Double-click **Glass Finder Setup.exe**. It installs to your own user folder (no admin rights needed), adds a Desktop and Start Menu shortcut, and opens the app once it's done. Everything it needs is bundled in, so it works on a fresh Windows PC with nothing else installed first.

It also adds a second Start Menu shortcut, **Glass Finder (phone access)** — see "Using it from a phone" below.

If you're ever given an updated installer, running it again upgrades the app in place and always leaves your phone list and photos exactly as they are — nothing is lost.

To remove it: Windows Settings → Apps, find **Glass Finder**, Uninstall. Your data is kept even after uninstalling (it tells you where); delete that folder yourself if you don't want it any more.

## Activating and signing in

Each copy is locked to the one PC it's set up on and won't run on a different computer. The first time you open it, the screen shows an ID for that PC and asks for an **activation code** — send that ID to whoever provided you the app, and they'll send back a code that works only for this PC. Paste it in, then choose your own username and password right there; nobody else sees or picks it.

After that, opening the app always asks you to sign in first. Change your password any time from `⋯ > Change password`. Signing in normally lasts until you close the app; tick **Remember me on this device** when signing in to stay signed in on that PC for 90 days even across restarts. Changing your password signs every remembered device out at once.

**Reinstalling Windows** on the same PC does not need a new code — the app just keeps working. If the app is ever moved to a genuinely different PC, it will say "locked to a different PC" instead: send the new ID shown there to whoever provided the app for a new code. Your username and password stay exactly the same either way.

**Backing up your activation:** `⋯ > Download activation backup` saves a small file that can restore your exact activation (including your password) if it's ever lost or corrupted — keep it somewhere safe. It only works to restore on the same PC; it isn't a way to unlock a different one.

## Using it from a phone

Use the **Glass Finder (phone access)** shortcut from the Start Menu instead of the normal one. A window opens and prints an address like:

```
On your phone (same Wi-Fi):  http://192.168.1.20:3737
```

Connect the phone to the **same Wi-Fi** as this PC, open that address in its browser, and it's the full app — use **Add to Home screen** from the browser menu for a one-tap icon. Close the window on the PC to stop it.

If Windows asks about the firewall the first time, choose **Private networks** and **Allow**.

**This mode has no login of its own** — while it's running, anyone on that Wi-Fi network can open the same address and view or change the list, no sign-in needed. Fine for your shop's own Wi-Fi; don't leave it running on a guest network.

## Everyday use

- **Language**: `⋯ > العربية` switches the whole app to Arabic (right-to-left, with correct Arabic grammar for counts — "نموذج واحد" for one, "3 نماذج" for three, "11 نموذجًا" for eleven, and so on). Phone model names, brand names and box numbers are never translated, since they're your actual product data, not app text; switch back any time from the same menu. Your choice is remembered per device.
- **Theme**: by default the app follows Windows' own light/dark setting. `⋯` shows a button naming whatever it'll switch to next — tap it to cycle Light → Dark → Match Windows → Light… — so you can force a theme instead of following Windows if you want. Remembered per device.
- **Search**: just type. Spaces, capitals and dashes don't matter (`hot10play`, `HOT 10 play`). Brand shorthand works (`rm note 11`, `sam a52`). Misspellings get "Did you mean" suggestions.
- **Box number**: the yellow tags. Tap one to see every model that shares that glass.
- **All models**: a tile on the home screen next to the boxes, showing every model in the shop with its photo, grouped by brand. A model without a photo shows a blank silhouette instead — a quick way to spot which ones still need one. Inside a specific box, models are shown the same way (photo + name) instead of plain text.
- **Delete a box**: open the box, **Delete box N**. A model that only lived in that box is deleted outright; a model also listed in other boxes just loses that one tag and stays in the others. It tells you exactly which before you confirm. Nothing is really gone, though — see "Restoring a deleted box" below.
- **Restoring a deleted box**: `⋯ > Deleted boxes` lists every box you've deleted, most recent first, with **Restore** (brings back everything that was in it, including its note and stock count) and **Forget** (permanently drops the record). Tap a deleted box's name to see the models it held with their photos — the ones still shown solid are still elsewhere in your catalog (tap one to jump straight to it); faded ones only lived in that box and are gone for good. Searching for a model that used to be in a deleted box also shows a note with a one-tap **Restore** — handy when a customer asks for something you're out of and you get that glass back in stock later.
- **Stock count**: on a box's page, **In stock** is an optional number for how many of that glass are left. It shows on the box tile too, and turns red at zero, so you know what to reorder.
- **Sold one**: a one-tap **−1** next to the stock count on a box's page, and on every search result card whose box tracks stock — so ringing up a sale updates the count in the same motion, no separate trip to the box page. It floors at zero and refuses (with a clear message) on a box that isn't tracking stock at all.
- **Low stock**: `⋯ > Low stock` lists every box at or below a threshold you set right there (2 by default), lowest first, each with its own **Sold one** and **Open box**. The `⋯` badge shows how many boxes qualify.
- **Reorder report**: `⋯ > Reorder report` combines that low-stock list with **Searches with no result** into one view — what's low or missing that people keep asking for — with a **Copy for a supplier call** button that puts a plain-text summary on the clipboard.
- **Add models**: `+ Add models`, pick the box, paste the text. `/`, `;` and new lines all separate models, and `SAM`, `RM`, `XM`, `OP`, `VI`, `INF`, `TEC`, `HW`, `MT`, `APPLE`/`IPHONE` set the brand for the names after them. You get a preview to check before anything is saved. A model that already exists in another box is simply added to this box too.
- **A brand that isn't in the list**: **Brand for names without one** and the detail dialog's **Brand** field both accept any text you type, not just the built-in ones — type a new brand once (e.g. a manufacturer with no built-in shorthand) and it's suggested everywhere from then on.
- **Unknown brand**: brand is a best-effort guess and is sometimes wrong or blank. `⋯ > Unknown brand` lists every model that needs a look, with a one-tap **Fix** that opens it for editing. The `⋯` badge shows how many are left.
- **Merge two models**: when the same phone ended up as two slightly different entries (a typo, a duplicate), open either one and use **Merge into another model…**, search for its twin, and pick it. Every box the deleted one was in moves onto the one you kept, and its photo is inherited if the kept one didn't have one yet. This cannot be undone.
- **Frequently searched**: the home screen shows the models looked up most often, right above the box list, so the common ones are one tap away instead of retyping.
- **Jump to a box by number**: typing `#12` or `box 12` in the search bar (or `صندوق 12` in Arabic) opens that box directly, for when staff already know the box number.
- **Searches with no result**: when you search for a phone that isn't in any box, it is logged (once you pause typing) and counted if searched again. Open `⋯ > Searches with no result` to see what people ask for that you don't stock, most-searched first. `Add to a box` sends it to the Add screen; once a search would find something, it drops off the list on its own. Opening a "Did you mean" suggestion counts the search as a typo and removes it.
- **Fix something**: tap a model to rename it, change its brand or boxes, change its photo, or delete it.
- **Photos**: fetched from the web in the background for models you look at (or `⋯ > Get missing photos` for all of them). By default this uses a free, keyless search that occasionally gets rate-limited and needs manual picking more often; `⋯ > Photo settings` lets you add a free [serper.dev](https://serper.dev) API key (no card needed, 2,500 free searches — comfortably covers a whole catalogue) to make it fast and reliable instead. If the wrong picture is chosen, open the model and use **Change photo** to pick another, paste a link, or upload your own.

## Staff accounts and the activity log

The person who activates the PC is the **admin**. From `⋯ > Manage staff…`, an admin can add a **staff** account (their own username and password), reset anyone's password without knowing the old one, and remove an account. A staff account can do everyday work — search, add models, sell stock, delete/restore boxes — but the admin-only screens (`Manage staff…`, `Activity log`, and clearing the deleted-boxes list) stay hidden from them, and the app refuses those requests even if asked directly.

`⋯ > Activity log` (admin only) lists who did what and when — adding models, editing or deleting a model, merging two models, deleting or restoring a box, clearing the deleted-boxes list, and staff account changes — most recent first, so you can see which staff member made a given change. Resetting someone's password or removing their account signs out every device they were remembered on.

`⋯ > Deleted boxes` also has a **Clear list** button for admins, for wiping the whole deleted-boxes history at once (each entry can still be forgotten one at a time by anyone, as before). Both are permanent — restoring is no longer possible for anything cleared.

## Your data

Everything is in the `data` folder next to the app: `db.json` (your model list) and `images`. A copy of `db.json` is saved automatically into `data/backups` once a day (last 30 kept).

`⋯` menu: download your list as a `.csv` for Excel, or download/restore a full backup file.

## Getting help

If something isn't working, you need a new activation code, or you'd like a feature added, contact whoever provided you this app.
