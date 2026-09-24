# Discord Split View

An LSPosed/Xposed module for the **Discord Android client** (`com.discord`) that
adds a split-screen interface for viewing a second channel or DM alongside the
one open in the main view.

## Features

- **Floating toggle button** injected into Discord's UI — tap to open/close the
  split pane, drag to reposition it.
- **Resizable split pane** docked to the right edge, with a draggable divider
  (25–75% of screen width).
- **Second session in a WebView** pointed at discord.com, signed in
  automatically with the app's own auth token.
- **"Follow" button** that mirrors whatever channel/DM you last opened in the
  main view into the pane (resolved via the REST API, works for guild channels,
  DMs, and group DMs).
- Multi-channel and multi-DM viewing without switching context in the main app.

## How it works

- `XposedInit` hooks `Activity.onResume`/`onDestroy` inside the `com.discord`
  process and attaches a `SplitViewController` to each Discord activity.
- `DiscordApiTracker` hooks `okhttp3.Request$Builder` (the HTTP stack used by
  React Native's networking layer) to passively capture the `Authorization`
  header and the most recently viewed channel id.
- `TokenStore` falls back to scanning Discord's SharedPreferences, RN
  AsyncStorage (`catalystLocalStorage`), and blob stores (e.g. MMKV) for the
  token when no authenticated request has been seen yet.
- The pane's `WebViewClient` intercepts the first navigation to
  `https://discord.com/` and serves a bootstrap page that writes the token into
  `localStorage` on the discord.com origin, then loads the real web app with a
  desktop user agent.

## Requirements

- Rooted Android device (or emulator) with **LSPosed** (or another Xposed
  framework) installed.
- Discord installed. Enable the module in LSPosed manager with scope
  **Discord**, then force-stop and relaunch Discord.

## Build

Requires JDK 17 and the Android SDK (platform 34, build-tools 34).

```bash
./gradlew assembleDebug
```

Produces `app/build/outputs/apk/debug/app-debug.apk`.

## Notes / limitations

- The pane shows Discord's **web app** inside the client; it shares the app's
  login, so anything you can open on discord.com works (channels, DMs, search).
- The token capture depends on Discord's okhttp stack; if a future build
  switches networking stacks, the storage-scan fallbacks still apply and the
  pane falls back to a login page.
- First-party native embedding of a second React Native surface is a possible
  future direction but significantly more invasive.
