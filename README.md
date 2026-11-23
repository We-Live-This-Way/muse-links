# WLTW Links

WLTW Links creates temporary short links on your own website from your phone. Paste any destination, pick or generate a short code, and the app adds a 302 redirect through the cPanel redirects API. The link is a real server redirect, so it works no matter what the site itself is built with — WordPress today, something else tomorrow. No WordPress plugin is required.

This tree is **v1.4** (`versionName` 1.4, `versionCode` 5).

## What you need

- An Android phone running Android 7.0 or newer
- A website hosted on cPanel
- A cPanel API token (created in cPanel → Security → Manage API Tokens)

## Install

1. Download `WLTW-Links-1.4.apk` from the [latest release](https://github.com/We-Live-This-Way/muse-links/releases/latest).
2. On the phone, open the file. If Android asks, allow installs from your browser or file manager, then tap Install.
3. Open the app, go to **Settings**, and enter your cPanel host, domain, username, and API token.

If you already installed an older test or debug build, uninstall it first. This release is signed with a new key, so it cannot update the old app in place. You will need to re-enter your cPanel settings.

A cPanel API token is powerful — treat it like a password, keep it only in the app, and revoke it if the phone is lost. No token is stored in this repository.

## Set up

| Field | Value |
|---|---|
| cPanel host | your cPanel hostname (the server hostname, not necessarily the website domain) |
| Domain | the domain that should serve the short links, e.g. `example.com` |
| cPanel username | your cPanel username |
| API token | create one in cPanel → Security → Manage API Tokens (shown once) |

Tap **Save settings**.

## Make a link

1. **Code** — type one (lowercase letters, numbers, dashes, 3–32 characters) or tap **Random**, which makes a short code for you.
2. **Destination link** — paste it. The app adds `https://` if you leave it off.
3. Tap **Create link**. The app shows the short URL with **Copy link** and **Open link**.
4. **Recent links** lists the redirects on the account (newest first), each with Copy / Open / **Delete**. A redirect cannot be edited in cPanel — to change one, delete it and create it again.

## WordPress 404s (fixed automatically)

cPanel writes each redirect as a rule at the **bottom** of the domain's `.htaccess` file. On a WordPress site, WordPress's rules often sit above that and catch a new short code first, so the visitor sees a WordPress 404 instead of the redirect.

The app fixes this itself:

- **After every successful Create**, and when you tap the **Fix links** button, the app reads `public_html/.htaccess`, backs it up, moves the cPanel redirect blocks (`RewriteCond` / `RewriteRule` lines) to the very top of the file, above `# BEGIN WordPress`, saves it, then re-reads it to verify.
- **Backup:** before changing anything, the app saves the original `.htaccess` to `public_html/.htaccess.bak-wltw`. If the backup or the save fails, it reports the server's message and leaves `.htaccess` unchanged.
- One caveat: on some hosts `save_file_content` cannot create a brand-new file, so if the backup step fails, create an empty `.htaccess.bak-wltw` file in File Manager first, then tap Fix links again.
- The app blocks a few reserved codes (`hello`, `pay`, and `track`) so they cannot collide with common existing paths.
- If a link still shows an old 404 after the fix, purge any CDN or site cache and test in a private/incognito window.

If the automatic fix ever fails, do the same move by hand in cPanel → File Manager, file `public_html/.htaccess`: move the redirect `RewriteCond` / `RewriteRule` lines to the very top, above `# BEGIN WordPress`, save, then test the short link in a private window. Keep the backup in `.htaccess.bak-wltw`.

## How it works (API calls)

Base URL: `https://<cpanel-host>:2083/execute/`
Auth header on every call: `Authorization: cpanel <username>:<api-token>`

cPanel's redirect functions live in the **Mime** module.

- **Create** — `POST /execute/Mime/add_redirect`
  form params: `domain=example.com`, `src=/abc123`, `redirect=https://…`, `type=temp`, `redirect_www=0`, `redirect_wildcard=0`. If cPanel rejects those with a parameter error, the app retries once with the older alias names `source` / `destination` / `redirect_type=temporary`.
- **List** — `GET /execute/Mime/list_redirects`
- **Delete** — `POST /execute/Mime/delete_redirect` with `domain` and `src`

- **Fix links (.htaccess)** — `Fileman` module, same auth:
  - `GET /execute/Fileman/get_file_content` for `public_html/.htaccess`
  - `POST /execute/Fileman/save_file_content` to write `.htaccess.bak-wltw` first
  - `POST /execute/Fileman/save_file_content` to write the reordered `.htaccess`
  - then `get_file_content` again to verify the redirect blocks are above `# BEGIN WordPress`

Links are created as **302 Temporary** on purpose: browsers cache 301 (permanent) redirects aggressively even after the redirect is deleted.

**Test one link first**: create it in the app, check it appears in cPanel → Domains → Redirects, then open it.

## Build from source

The Gradle project lives in `android/`. On a machine with JDK 17 and the Android SDK (compileSdk 34, minSdk 24):

```bash
cd android
gradle assembleDebug
```

A command-line script is also included:

```bash
cd android
./build-apk-manual.sh
```

The script expects a local Android SDK (`$HOME/Android/Sdk`) and may need its JDK / build-tools paths adjusted. Official release APKs on GitHub are signed with a dedicated release key kept off this repository. If you build your own APK with a different key, it will not install over the official release (uninstall first).
