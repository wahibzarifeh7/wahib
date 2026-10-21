# MapMouse Pro

Play touch-screen-only Android games with a physical keyboard, mouse or gamepad (USB-OTG or Bluetooth).
Its features follow GG Mouse Pro 2. Everything is unlocked: there are no accounts, subscriptions or ads.

## Keymap controls (placed over the game in the on-screen editor)
| Control | What it does |
|---|---|
| Touch | Types: Press (hold), Tap, Down/Up tap, Long-press trigger, Up trigger. Options: switch mouse pointer (on down/up/both), reset view, reset joystick, trigger recoil |
| Joystick | WASD/arrows/custom keys, radius, switch-radius (walk) key in long-press or click mode, movement steps |
| View / Aim (FPS) | H/V sensitivity, a switched sensitivity with its own hotkey (hold/click), range, release only at the edge or after idle, mouse acceleration (coefficient, minimum speed, limit), reset-view key |
| Free look | Hold a key and the mouse drags the eye button, with sensitivity and reset on edge |
| Swipe | Single or round way, from pointer, horizontal only, tap before/after |
| Mouse scroll | The wheel drags a finger, with speed, length, invert and auto release |
| Rapid tap | Interval and tap duration |
| Crazy tap | Overlapping alternating fingers, interval and duration. It can be conditional: only active for chosen weapons |
| Macro | Tap, Press, Release, Swipe, Sleep, Mouse display, Reset view, Reset joystick, Recoil start/stop. Triggers: key down, key up, loop, down/up. A per-macro terminate key can also disable the macro. Macros are shared via codes |
| Long press group | Stages are pressed at set times while the key is held. Previous stages are terminated or kept. A short press taps the control |
| Trigger group | Holding a key switches some keys to a second layout |

## Recoil control
- Several weapon configs, each with vertical and horizontal values, a select key (e.g. 1/2) and a crazy-tap flag.
- Optional per-weapon **curve** of strength over time (dynamic recoil).
- Global strength %, start delay, fire key. Touch controls and macros can also trigger recoil.
- **Stop when not aiming**: aim key in hold or click mode.
- **Horizontal recoil control**: on/off key in press or switch mode.
- **Stance factors**: crouch and prone keys.
- Adjust while playing with **Ctrl + mouse wheel** or the +/− hotkeys. An on-screen recoil panel can be toggled.

## Mouse & overlays
- Cursor mode (Toggle or Hold). The pointer appears at the center, last position or a custom spot.
- Pointer styles (arrow, dot, ring, cross, outline or **custom image**), plus size, color and speed.
- Mouse max polling rate (125/250/500 Hz/no limit) and mouse smoothness.
- HUD: key labels (F9), touch paths, recoil panel, label color/opacity, floating-button opacity.
- Crosshair: 5 styles, RGB color, size, opacity, offsets, hide when aiming.
- Device monitor: FPS, CPU %, CPU °C, battery, network speed, router latency, clock and play time. It can be fixed or draggable, at 3 refresh rates. Session records with graphs.

## Other
- **My games**: launch games with their profile. The profile is switched automatically when a game comes to the front. Optional per-game screen size/density override, reset when you leave the game.
- **Keyboard typing mode**: type in chat while the mouse keeps playing.
- **Touch compatible mode**: touch the screen while mapping is on.
- **Gamepad**: left stick → joystick, right stick → view. Buttons, D-pad and triggers can be bound.
- Hotkeys: mapping on/off (F12), cursor (`), HUD, typing, show/hide floating button, crazy tap on/off, stop macros.
- Profiles: share, import from a file or clipboard, duplicate.
- **Activation via Shizuku**: start Shizuku with wireless debugging, a PC (adb) or root.

## Not included
- AI weapon/scope/attachment recognition. GG uses OpenCV screen analysis tuned per game. Here you pick weapons with their slot keys instead.
- Online accounts, cloud keymaps and official keymap servers. Profiles are shared as text instead.
- GG's own hardware adapters and their firmware updates.
- Streamer mode.

## How it works
`server/InputService` runs as a **Shizuku UserService** with shell (ADB) or root rights. It:
- reads `/dev/input/event*`;
- grabs devices (`EVIOCGRAB`) so the game only sees the mapped touches;
- turns input into multi-touch events via `server/Engine`;
- injects them with `InputManager.injectInputEvent`.

The app talks to it over Binder (`IInputService.aidl`).

## Build
Run `build.bat` (it uses the toolchain in `D:\APK\toolchain`), or open the folder in Android Studio.
The output is `app/build/outputs/apk/debug/app-debug.apk`.
