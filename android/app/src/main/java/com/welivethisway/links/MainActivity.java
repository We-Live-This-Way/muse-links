package com.welivethisway.links;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Single-screen app: create a temporary cPanel redirect
 * https://welivethisway.com/{code}  ->  destination URL pasted in the app.
 *
 * Settings (saved on the phone, nothing is sent anywhere except to the
 * cPanel host entered below):
 *   cPanel host  — the server's cPanel hostname. For this account the
 *                  cPanel login redirects to your-cpanel-host.example.com,
 *                  so that is the default. The domain (welivethisway.com)
 *                  is a separate field; the two are often different.
 *   Domain       — the domain the short link lives on.
 *   cPanel username / API token — cPanel > Security > Manage API Tokens.
 */
public class MainActivity extends Activity {

    // Defaults the operator supplied. All editable in Settings; no API token here.
    private static final String DEFAULT_HOST = "";
    private static final String DEFAULT_DOMAIN = "welivethisway.com";
    private static final String DEFAULT_USER = "";

    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9-]{3,32}$");
    private static final Random RANDOM = new Random();

    // Codes that already exist on the site as WordPress pages or as
    // existing permanent redirects. "hello" is WordPress's default
    // Hello World post, so /hello shows that post instead of redirecting.
    // "pay" and "track" are existing permanent 301 redirects — recreating
    // them would conflict with the live links.
    private static final String[] RESERVED_CODES = {"hello", "pay", "track"};

    private SharedPreferences prefs;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private EditText etHost, etDomain, etUser, etToken;
    private EditText etCode, etDest;
    private TextView tvStatus, tvCreatedLink;
    private LinearLayout layoutCreated, layoutRecent;
    private Button btnCopyLink, btnOpenLink;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("wltw", MODE_PRIVATE);

        etHost = findViewById(R.id.etHost);
        etDomain = findViewById(R.id.etDomain);
        etUser = findViewById(R.id.etUser);
        etToken = findViewById(R.id.etToken);
        etCode = findViewById(R.id.etCode);
        etDest = findViewById(R.id.etDest);
        tvStatus = findViewById(R.id.tvStatus);
        tvCreatedLink = findViewById(R.id.tvCreatedLink);
        layoutCreated = findViewById(R.id.layoutCreated);
        layoutRecent = findViewById(R.id.layoutRecent);
        btnCopyLink = findViewById(R.id.btnCopyLink);
        btnOpenLink = findViewById(R.id.btnOpenLink);

        etHost.setText(prefs.getString("host", DEFAULT_HOST));
        etDomain.setText(prefs.getString("domain", DEFAULT_DOMAIN));
        etUser.setText(prefs.getString("user", DEFAULT_USER));
        etToken.setText(prefs.getString("token", ""));
        etCode.setText(randomCode());

        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> saveSettings());
        findViewById(R.id.btnRandom).setOnClickListener(v -> etCode.setText(randomCode()));
        findViewById(R.id.btnCreate).setOnClickListener(v -> createLink());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> refreshRecent());
        findViewById(R.id.btnFixLinks).setOnClickListener(v -> fixLinks(null));
        btnCopyLink.setOnClickListener(v -> copy(tvCreatedLink.getText().toString()));
        btnOpenLink.setOnClickListener(v -> openUrl(tvCreatedLink.getText().toString()));

        refreshRecent();
    }

    /** 2 lowercase letters + 5 digits, e.g. jh33431 */
    private static String randomCode() {
        StringBuilder sb = new StringBuilder(7);
        for (int i = 0; i < 2; i++) sb.append((char) ('a' + RANDOM.nextInt(26)));
        for (int i = 0; i < 5; i++) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }

    private void saveSettings() {
        prefs.edit()
                .putString("host", cleanHost(etHost.getText().toString()))
                .putString("domain", cleanDomain(etDomain.getText().toString()))
                .putString("user", etUser.getText().toString().trim())
                .putString("token", etToken.getText().toString().trim())
                .apply();
        etHost.setText(prefs.getString("host", DEFAULT_HOST));
        etDomain.setText(prefs.getString("domain", DEFAULT_DOMAIN));
        status("Settings saved.", true);
        refreshRecent();
    }

    private static String cleanHost(String h) {
        h = h.trim().toLowerCase();
        h = h.replaceFirst("^https?://", "");
        h = h.replaceFirst("^www\\.", "");
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        if (h.endsWith(":2083")) h = h.substring(0, h.length() - 5);
        if (h.endsWith(":2082")) h = h.substring(0, h.length() - 5);
        return h;
    }

    private static String cleanDomain(String d) {
        d = d.trim().toLowerCase();
        d = d.replaceFirst("^https?://", "");
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        return d;
    }

    /** Accept google.com, www.google.com, https://google.com, etc.
     *  Returns a full https:// URL, or null if it is not a usable link.
     *  A scheme the user typed is kept (http:// stays http://); if no
     *  scheme was typed, https:// is added. */
    private static String normalizeDestination(String raw) {
        if (raw == null) return null;
        String d = raw.trim();
        if (d.isEmpty() || d.contains(" ")) return null;
        if (d.startsWith("//")) d = "https:" + d;
        else if (!d.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) d = "https://" + d;
        android.net.Uri uri = android.net.Uri.parse(d);
        String host = uri.getHost();
        if (host == null || !host.contains(".")) return null;
        return d;
    }

    private CpanelApi apiOrNull() {
        String host = prefs.getString("host", DEFAULT_HOST);
        String user = prefs.getString("user", DEFAULT_USER);
        String token = prefs.getString("token", "");
        if (host.isEmpty() || user.isEmpty() || token.isEmpty()) {
            status("Fill in cPanel host, username and API token in Settings first.", false);
            return null;
        }
        return new CpanelApi(host, user, token);
    }

    private void createLink() {
        CpanelApi api = apiOrNull();
        if (api == null) return;
        String domain = prefs.getString("domain", DEFAULT_DOMAIN);
        String code = etCode.getText().toString().trim().toLowerCase();
        String dest = normalizeDestination(etDest.getText().toString());
        if (!CODE_PATTERN.matcher(code).matches()) {
            status("Code must be 3–32 lowercase letters, numbers or dashes.", false);
            return;
        }
        for (String reserved : RESERVED_CODES) {
            if (code.equals(reserved)) {
                status("That code already exists on the site, pick another one", false);
                return;
            }
        }
        if (dest == null) {
            status("Enter a destination, like google.com or https://google.com", false);
            return;
        }
        etDest.setText(dest);
        if (domain.isEmpty()) {
            status("Enter the domain in Settings first.", false);
            return;
        }
        status("Creating https://" + domain + "/" + code + " …", true);
        bg.execute(() -> {
            try {
                CpanelApi.Result r = api.addRedirect(domain, code, dest);
                runOnUiThread(() -> {
                    if (r.ok) {
                        String link = "https://" + domain + "/" + code;
                        tvCreatedLink.setText(link);
                        layoutCreated.setVisibility(View.VISIBLE);
                        status("Created: " + link + " — fixing links so it works…", true);
                        etCode.setText(randomCode());
                        refreshRecent();
                        // WordPress answers new codes with a 404 before
                        // the cPanel redirect fires unless the redirect
                        // rules sit above WordPress in .htaccess.
                        // Run the fix automatically after every create.
                        fixLinks("Created: " + link);
                    } else {
                        layoutCreated.setVisibility(View.GONE);
                        status("Could not create it: " + r.message, false);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> status("Connection failed: " + e.getMessage(), false));
            }
        });
    }

    /**
     * Move the cPanel redirect blocks in .htaccess above WordPress.
     * @param prefix if not null (auto-fix after a create), the success /
     *               failure message is prefixed with it so the user still
     *               sees that the link was created. If null (manual tap
     *               on "Fix links"), the fix message stands on its own.
     */
    private void fixLinks(String prefix) {
        CpanelApi api = apiOrNull();
        if (api == null) return;
        if (prefix == null) status("Fixing links — moving redirects above WordPress…", true);
        bg.execute(() -> {
            try {
                CpanelApi.HtaccessResult hr = api.fixHtaccess();
                runOnUiThread(() -> {
                    String msg = prefix == null ? hr.message : prefix + ". " + hr.message;
                    status(msg, hr.ok);
                    if (hr.ok) refreshRecent();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    String msg = prefix == null
                            ? "Could not fix links: " + e.getMessage()
                            : prefix + ", but could not fix links: " + e.getMessage();
                    status(msg, false);
                });
            }
        });
    }

    private void refreshRecent() {
        CpanelApi api = apiOrNull();
        if (api == null) {
            layoutRecent.removeAllViews();
            return;
        }
        String domain = prefs.getString("domain", DEFAULT_DOMAIN);
        bg.execute(() -> {
            try {
                List<CpanelApi.Redirect> all = api.listRedirects();
                runOnUiThread(() -> {
                    layoutRecent.removeAllViews();
                    int shown = 0;
                    for (CpanelApi.Redirect rd : all) {
                        if (!domain.isEmpty() && !rd.domain.isEmpty()
                                && !rd.domain.equalsIgnoreCase(domain)
                                && !rd.domain.equalsIgnoreCase("ALL")) continue;
                        addRecentRow(rd, domain);
                        if (++shown >= 20) break;
                    }
                    if (shown == 0) {
                        TextView t = new TextView(this);
                        t.setText("No redirects yet for " + domain + ".");
                        t.setPadding(0, 8, 0, 8);
                        layoutRecent.addView(t);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> status("Could not list links: " + e.getMessage(), false));
            }
        });
    }

    private void addRecentRow(CpanelApi.Redirect rd, String domain) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 10, 0, 10);

        TextView title = new TextView(this);
        title.setText("https://" + domain + "/" + rd.code + "  (" + rd.type + " " + rd.statusCode + ")");
        title.setTextSize(15);
        row.addView(title);

        TextView dest = new TextView(this);
        dest.setText("→ " + rd.destination);
        dest.setTextSize(13);
        dest.setTextColor(Color.DKGRAY);
        row.addView(dest);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button copy = new Button(this);
        copy.setText("Copy");
        copy.setOnClickListener(v -> copy("https://" + domain + "/" + rd.code));
        btns.addView(copy);

        Button open = new Button(this);
        open.setText("Open");
        open.setOnClickListener(v -> openUrl("https://" + domain + "/" + rd.code));
        btns.addView(open);

        Button del = new Button(this);
        del.setText("Delete");
        del.setOnClickListener(v -> deleteLink(rd, domain));
        btns.addView(del);

        row.addView(btns);
        layoutRecent.addView(row);
    }

    private void deleteLink(CpanelApi.Redirect rd, String domain) {
        CpanelApi api = apiOrNull();
        if (api == null) return;
        status("Deleting /" + rd.code + " …", true);
        bg.execute(() -> {
            try {
                CpanelApi.Result r = api.deleteRedirect(domain, rd.code, rd.destination);
                runOnUiThread(() -> {
                    status(r.ok ? "Deleted /" + rd.code : "Could not delete: " + r.message, r.ok);
                    if (r.ok) refreshRecent();
                });
            } catch (Exception e) {
                runOnUiThread(() -> status("Connection failed: " + e.getMessage(), false));
            }
        });
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("link", text));
        Toast.makeText(this, "Copied: " + text, Toast.LENGTH_SHORT).show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open link: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void status(String msg, boolean ok) {
        if (msg != null && msg.length() > 300) msg = msg.substring(0, 300) + "…";
        tvStatus.setText(msg);
        tvStatus.setTextColor(ok ? Color.parseColor("#1B6B1B") : Color.parseColor("#B00020"));
        tvStatus.setGravity(Gravity.START);
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }
}
