# TIV Television app (Android phones + Android TV)

One APK for both:
- **Phones and tablets** show the phone app (`app/src/main/assets/web/index.html`).
- **Android TV / Google TV boxes** show the TV app (`tv.html`) and appear on the TV home screen; everything works with the remote's arrows, OK and Back.

The pages, fonts and the HLS player are inside the APK. Only the live stream comes from the internet.

## Getting the APK
Every push builds the app with GitHub Actions (**Actions → Build APK**); the APK is in the run's **Artifacts**. Pushes to `main` also publish it as a **Release** (`v1.0.<build number>`).

Install on a phone: open the `.apk`, allow "Install unknown apps" when asked.
Install on Android TV: copy the APK with a USB stick or the "Send files to TV" app, then open it with a file manager (or use `adb install`).

## Where the stream link comes from
At launch (and every 2 minutes while open, and whenever the stream drops) the app reads the link page set in `app/build.gradle`:

```
CONFIG_URL = "https://gazgoc.com.ng/tiv-app-link/"
```

That page can be an ordinary hidden web page containing lines like:

```
TIV_STREAM = https://something.trycloudflare.com/hls/live.m3u8
TIV_SHARE = https://www.facebook.com/tivtelevisionkase
TIV_PROGRAMME = 06:00 | Morning Devotion | Prayer and worship to start the day. | Faith
TIV_PROGRAMME = 07:00 | Tiv News at Seven | The morning news in Tiv and English. | News
```

- `TIV_STREAM` (required): the HLS (`.m3u8`) link. When it changes, open apps switch to it within about 2 minutes, with no update needed.
- `TIV_SHARE` (optional): the link the Share button sends.
- `TIV_PROGRAMME` (optional, one per line, in time order): replaces the built-in schedule. Format: `time | title | description | category`.

It can also be JSON: `{"stream": "...", "share": "...", "schedule": [{"time":"06:00","title":"...","desc":"...","tag":"..."}]}`.

If the page can't be reached, the app uses the last link it saw; on a first launch with no page it uses `FALLBACK_STREAM` (a public test stream).

**The stream server must allow cross-origin requests** (`Access-Control-Allow-Origin: *`). The PC kit's Caddyfile and MediaMTX both do this by default.

## Signing
Builds are signed with the development key `app/tiv-dev.keystore`, so each new build installs over the previous one. Before publishing on Google Play, create a private release key and add these repository secrets (Settings → Secrets and variables → Actions): `TIV_KEYSTORE_BASE64` (the keystore file, base64), `TIV_KEYSTORE_PASSWORD`, `TIV_KEY_ALIAS`, `TIV_KEY_PASSWORD`. Switching keys means testers uninstall the test version once.

## Updating the design
Edit `index.html` / `tv.html` in `app/src/main/assets/web/`. They are the same pages as the web version, with these app additions: local fonts and hls.js, `APP_CFG` (settings from the app), `window.tivSwitchStream` and `window.tivBack`.

## Still to replace
- App icon and TV banner (`app/src/main/res/mipmap-*/ic_launcher.png`, `drawable-xhdpi/banner.png`) are made from the small logo in the web pages; swap in a high-resolution logo when TIV sends it.
