# WeLiveThisWay Links

An Android app that creates temporary short links on your site (for example `https://welivethisway.com/abc123`) by calling the cPanel redirects API.

Built by Muse-proto.

No APK is included; build from source. Gradle `versionName` in this tree is `1.0`.

## Build

```bash
cd android
gradle assembleDebug
# or:
./build-apk-manual.sh
```

The manual script expects a local Android SDK (`$HOME/Android/Sdk`) and may need its JDK / build-tools paths adjusted.

## Set up

In the app, fill in Settings (nothing account-specific is pre-filled in this public source):

| Field | Value |
|---|---|
| cPanel host | your cPanel hostname (the server hostname, not necessarily the website domain) |
| Domain | e.g. `welivethisway.com` |
| cPanel username | your cPanel username |
| API token | create one in cPanel → Security → Manage API Tokens (shown once) |

A cPanel API token is powerful — treat it like a password, keep it only in the app, and revoke it if the phone is lost. No token is stored in this repository.

See the rest of this README for how redirects are created and listed.

---

## Original notes

# WeLiveThisWay Links — temporary short links via cPanel

Create a temporary link like `https://welivethisway.com/jh33431` that sends a
customer to any link you paste in. The link is a real **cPanel redirect**
(302 Temporary), so it works no matter what the website itself is built with —
WordPress today, something else tomorrow. No WordPress plugin is involved.

## Files

| File | What it is |
|---|---|
| `WeLiveThisWay-Links.apk` | The Android app (debug build, install it below) — v1.4, with the site logo, fixed cPanel reply parsing, the automatic .htaccess fix, and bare-domain destinations |
| `site-logo.jpg` | The original site art from welivethisway.com, used in the app |
| `android/` | Full Android project source (Gradle) + `build-apk-manual.sh` |

## Branding

The app uses your site art — the "WE LIVE THIS WAY / A SOUTH FLORIDA
COURIER CO" poster with the teal car, sunset and palms — as the header
banner at the top of the screen, in the app icon (sun/car crop on navy),
and for the colours: navy `#12263F`, teal `#1E8A9B` for the Create button,
cream `#FFF8E8` background. The art files live in
`android/app/src/main/res/drawable/` (`wltw_banner.jpg`, `wltw_logo.jpg`)
and the icons in `res/mipmap-*/ic_launcher.png`.

## 1. Install the app

1. Copy `WeLiveThisWay-Links.apk` to the Android phone (USB, email it to
   yourself, Google Drive, etc.).
2. On the phone, open the APK and allow **Install unknown apps** for the app
   you open it from, if asked. Tap Install.
3. The app is called **WeLiveThisWay Links**. It asks for no permissions
   except Internet, and sends nothing anywhere except to your cPanel server.

## 2. Create a cPanel API token (one time)

1. Log in to cPanel. Your cPanel login redirects to
   **your-cpanel-host.example.com:2083** (going to `welivethisway.com/cpanel`
   lands on the same server).
2. In cPanel go to **Security > Manage API Tokens** and create a token,
   e.g. named `WLTW Links App`.
3. **Copy the token right away** — cPanel only shows it once.

Token permissions to know about:
- The cPanel account needs the **Redirects** feature enabled. If the app
  says `You do not have the feature "redirects"`, the host has to enable it
  for the account (WHM > Feature Manager).
- A cPanel API token is **not scoped to redirects only** — it can do
  anything the cPanel user can do in the features enabled for the account.
  Treat it like a password: keep it only in the app, and revoke it in
  cPanel > Manage API Tokens if the phone is ever lost.
- No token is stored in this folder or in the app download. You paste it
  into the app yourself (step 3).

## 3. Enter the details in the app (Settings, top of the screen)

| Field | Value |
|---|---|
| cPanel host | your cPanel hostname (the server hostname, not necessarily the website domain) |
| Domain | `welivethisway.com` (already filled in) |
| cPanel username | your cPanel username |
| API token | paste the token from step 2 |

Tap **Save settings**. If the host ever changes, change only the
*cPanel host* field — the domain stays `welivethisway.com`.

## 4. Make a link

1. **Code** — type one (lowercase letters, numbers, dashes, 3–32 chars) or
   tap **🎲 Random**, which makes one like `jh33431` (2 letters + 5 digits).
2. **Destination link** — just type or paste it, like `google.com`,
   `www.google.com`, or `https://google.com`. The app adds `https://`
   for you if you leave it off.
3. Tap **Create link**. The app shows `https://welivethisway.com/jh33431`
   with **Copy link** and **Open link**.
4. **Recent links** below lists the redirects on the account (newest first),
   each with Copy / Open / **Delete**. A redirect cannot be edited in
   cPanel — to change one, delete it and create it again.

## ⚠️ WordPress 404s — fixed automatically in v1.3 (read this first)

cPanel writes each redirect as a rule at the **bottom** of the domain's
`.htaccess` file. On this site WordPress's rules sit above it and catch
a new short code first: `/ed35603` gave a WordPress "Page Not Found",
and `/hello` showed WordPress's default "Hello world!" post instead of
redirecting. `/pay` and `/track` (older permanent redirects) do work.

The app now fixes this itself, in v1.3:

- **After every successful Create**, and when you tap the **Fix links**
  button next to Recent links, the app reads `public_html/.htaccess`,
  backs it up, moves the cPanel redirect blocks (`RewriteCond` /
  `RewriteRule` lines) to the very top of the file, above
  `# BEGIN WordPress`, saves it, then re-reads it to verify the blocks
  are now above WordPress. The status line reports the result, e.g.
  "Fixed links: moved 3 redirect blocks above WordPress."
- **Backup:** before changing anything, the app saves the original
  `.htaccess` to `public_html/.htaccess.bak-wltw`. If the backup or the
  save fails, it reports the server's message, leaves `.htaccess`
  unchanged, and does **not** retry blindly — the backup stays in place
  so you can restore it in File Manager. One caveat: on some hosts
  `save_file_content` cannot create a brand-new file, so if the backup
  step fails, create an empty `.htaccess.bak-wltw` file in File Manager
  first, then tap Fix links again.
- **Reserved codes:** `hello`, `pay` and `track` are blocked in the app
  with "That code already exists on the site, pick another one".
  `hello` is WordPress's default Hello World post, and `pay` / `track`
  are the existing permanent 301 redirects — recreating them would
  conflict with the live links.
- If a link still shows the old 404 after the fix, purge the cache: the
  site sits behind Cloudflare and LiteSpeed cache, which can hold on to
  the 404 page. Test in a private/incognito window.

This fix was **not** tested against the live cPanel server from this
computer — port 2083 times out from this VM — so test it from the phone:
create a link, check the status says it was fixed, then open the short
link in a browser.

## How it works (exact API calls)

Base URL: `https://<cpanel-host>:2083/execute/`
Auth header on every call: `Authorization: cpanel <username>:<api-token>`

> Correction: cPanel's redirect functions live in the **Mime** module, not
> a "Redirect" module.

- **Create** — `POST /execute/Mime/add_redirect`
  form params: `domain=welivethisway.com`, `src=/jh33431`,
  `redirect=https://…`, `type=temp`, `redirect_www=0`, `redirect_wildcard=0`
  (per api.docs.cpanel.net Mime/add_redirect, v11.138. If cPanel rejects
  those with a parameter error, the app retries once with the older
  alias names `source` / `destination` / `redirect_type=temporary`.)
- **List** — `GET /execute/Mime/list_redirects`
  (verified against cPanel's API docs; returns `result.data[]` with
  `source`, `sourceurl`, `destination`/`targeturl`, `domain`, `type`,
  `statuscode`)
- **Delete** — `POST /execute/Mime/delete_redirect`
  form params: `domain=welivethisway.com`, `src=/jh33431`

- **Fix links (.htaccess)** — `Fileman` module, same auth:
  - `GET /execute/Fileman/get_file_content`
    `dir=/home/<cpanel-username>/public_html` (built from the cPanel username in
    Settings), `file=.htaccess`, `from_charset=_DETECT_`,
    `to_charset=_LOCALE_`
  - `POST /execute/Fileman/save_file_content`
    `dir=public_html`, `file=.htaccess.bak-wltw`, `content=<original>`
    (backup, first — only proceed if this succeeds)
  - `POST /execute/Fileman/save_file_content`
    `dir=public_html`, `file=.htaccess`, `content=<reordered>`,
    `from_charset=UTF-8`, `to_charset=UTF-8`
  - then `get_file_content` again to verify the redirect blocks are
    above `# BEGIN WordPress`. Like the Mime calls, the Fileman result
    may be bare (`status`/`data` at top level) or wrapped in
    `{"result": ...}` — the app accepts both. `save_file_content`
    goes through a small AdminBin buffer, so a very large `.htaccess`
    may fail to save; `.htaccess` is normally only a few KB.

Links are created as **302 Temporary** on purpose: these are temporary
delivery links, and browsers cache 301 (permanent) redirects aggressively
even after the redirect is deleted.

One parameter note: the app sends the documented `src` / `redirect` /
`type=temp` params first. If a cPanel version rejects that, the app
automatically retries once with the older alias names and shows the
server's message if both fail. This could not be tested against the
live server from here — port 2083 times out from this VM — so
**test one link first**: create it in the app, check it appears in
cPanel > Domains > Redirects, then open it.

## Manual .htaccess fix (only if the app's Fix links button cannot save)

If the automatic fix ever fails, do the same move by hand in cPanel >
File Manager, file `public_html/.htaccess` (see the v1.3 section above
for why): move the redirect `RewriteCond` / `RewriteRule` lines to the
very top of the file, above `# BEGIN WordPress`, save, then test the
short link in a private/incognito window. Keep the backup in
`.htaccess.bak-wltw`.

## Rebuilding the app

- On a normal machine: open `android/` in Android Studio, or run
  `gradle assembleDebug` (JDK 17, Android SDK, compileSdk 34, minSdk 24).
- In this VM the Gradle daemon could not run (raw TCP, including
  loopback, is intercepted here, so the Gradle client can never reach
  its daemon). The APK here was built with the SDK tools directly:
  `android/build-apk-manual.sh` (aapt2 → javac → d8 → zipalign →
  apksigner). It needs JDK 17 + Android SDK under `~/Android/Sdk` and
  produces the same app. The APK is signed with a **debug key**
  (`android/debug.keystore`, password `android`) — fine for sideloading,
  not for the Play Store; a store release needs its own release key.
