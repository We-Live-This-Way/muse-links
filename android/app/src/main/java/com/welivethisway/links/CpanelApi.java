package com.welivethisway.links;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal client for the cPanel UAPI "Mime" module (redirects).
 *
 * NOTE on the module name: cPanel's redirect functions live in the
 * "Mime" module, not a "Redirect" module:
 *   Mime::add_redirect, Mime::list_redirects, Mime::delete_redirect
 * Verified against api.docs.cpanel.net (Mime/list_redirects) and
 * cPanel support (Cpanel::UAPI::Mime::add_redirect / delete_redirect).
 *
 * Auth: header  Authorization: cpanel <username>:<api-token>
 * Token: cPanel > Security > Manage API Tokens. It is NOT scoped to
 * redirects in most cPanel versions — it can do anything the cPanel
 * user can do in the features enabled for that account, so treat it
 * like a password. The account needs the "Redirects" feature enabled
 * (WHM > Feature Manager) or the calls fail with
 * "You do not have the feature 'redirects'."
 *
 * add_redirect parameter names, per api.docs.cpanel.net
 * Mime/add_redirect (v11.138):
 *   domain, src ("/jh33431"), redirect (destination URL),
 *   type ("temp" = 302, "permanent" = 301),
 *   redirect_www (0 = with and without www), redirect_wildcard (0 = off).
 * addRedirect() tries those first. If cPanel rejects them with a
 * parameter error, it retries once with the older alias names
 * (source / destination / redirect_type) as a fallback. See README.md.
 *
 * WordPress / .htaccess fix (v1.3):
 *   cPanel appends each redirect RewriteRule at the bottom of
 *   public_html/.htaccess, below the WordPress rules, so WordPress
 *   answers a new short code with a 404 (or its own "Hello world!"
 *   post for /hello) before the redirect can fire. /pay and /track
 *   already work, but new codes do not. fixHtaccess() moves the
 *   cPanel redirect blocks to the very top of .htaccess, above
 *   "# BEGIN WordPress", via Fileman UAPI:
 *     Fileman/get_file_content  dir=/home/<user>/public_html
 *       file=.htaccess, from_charset=_DETECT_, to_charset=_LOCALE_
 *     Fileman/save_file_content dir=public_html
 *       file=.htaccess.bak-wltw, content=<original>  (backup, first)
 *     Fileman/save_file_content dir=public_html
 *       file=.htaccess, content=<reordered>,
 *       from_charset=UTF-8, to_charset=UTF-8
 *   then re-reads .htaccess to verify the redirect blocks are now
 *   above WordPress. If the backup or the save fails, it reports the
 *   server message and does NOT retry blindly; the backup is left
 *   in place. save_file_content goes through a small AdminBin
 *   buffer, so a very large .htaccess may fail to save — .htaccess
 *   is normally only a few KB, so this should fit.
 */
public class CpanelApi {

    public static class Result {
        public boolean ok;
        public String message = "";
        public JSONObject json;
    }

    public static class Redirect {
        public String code = "";        // e.g. jh33431 (no leading slash)
        public String source = "";      // e.g. /jh33431
        public String destination = ""; // full URL
        public String type = "";        // permanent / temporary
        public String statusCode = "";  // 301 / 302
        public String domain = "";
    }

    private final String host;   // cPanel hostname, e.g. welivethisway.com (no scheme, no port)
    private final String user;   // cPanel username
    private final String token;  // cPanel API token

    public CpanelApi(String host, String user, String token) {
        this.host = host.trim();
        this.user = user.trim();
        this.token = token.trim();
    }

    private String baseUrl() {
        return "https://" + host + ":2083/execute/";
    }

    /** One UAPI call. GET for list, POST (form-encoded) for add/delete. */
    private Result call(String func, Map<String, String> params, boolean post)
            throws IOException, JSONException {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(URLEncoder.encode(e.getKey(), "UTF-8"));
            query.append('=');
            query.append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        String urlStr = baseUrl() + func;
        if (!post && query.length() > 0) urlStr += "?" + query;
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Authorization", "cpanel " + user + ":" + token);
        c.setRequestProperty("Accept", "application/json");
        if (post) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] body = query.toString().getBytes(StandardCharsets.UTF_8);
            c.setRequestProperty("Content-Length", Integer.toString(body.length));
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
        }
        int http = c.getResponseCode();
        InputStream in = http >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = readAll(in == null ? c.getInputStream() : in);
        c.disconnect();

        Result r = new Result();
        if (http == 401 || http == 403) {
            r.ok = false;
            r.message = "cPanel rejected the login (HTTP " + http + "). Check username / API token.";
            return r;
        }
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (JSONException e) {
            r.ok = false;
            r.message = "cPanel did not return JSON (HTTP " + http + "). Is the host the cPanel server?";
            return r;
        }
        // cPanel UAPI normally wraps the outcome in {"result": {...}},
        // but this server returns the result object bare, with
        // {"status": 1, "data": [...], "errors": null, ...} at the top
        // level. Accept either shape, and store the normalised result
        // object in r.json so listRedirects() can read data from it.
        JSONObject res = root.optJSONObject("result");
        if (res == null && root.has("status")) res = root;
        if (res == null) {
            r.ok = false;
            r.message = "Unexpected cPanel reply (no result/status).";
            return r;
        }
        r.json = res;
        r.ok = res.optInt("status", 0) == 1;
        JSONArray errors = res.optJSONArray("errors");
        if (errors != null && errors.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < errors.length(); i++) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(errors.optString(i));
            }
            r.message = sb.toString();
        } else if (!r.ok) {
            r.message = "cPanel returned status 0 with no error message.";
        } else {
            JSONArray msgs = res.optJSONArray("messages");
            if (msgs != null && msgs.length() > 0) r.message = msgs.optString(0);
        }
        return r;
    }

    private static String readAll(InputStream in) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }

    /**
     * Create a 302 (temporary) redirect: https://{domain}/{code} -> destination.
     * 302 is used deliberately: these are temporary delivery links, and
     * browsers cache 301s aggressively even after the redirect is deleted.
     */
    public Result addRedirect(String domain, String code, String destination)
            throws IOException, JSONException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("domain", domain);
        p.put("src", "/" + code);
        p.put("redirect", destination);
        p.put("type", "temp");
        p.put("redirect_www", "0");
        p.put("redirect_wildcard", "0");
        Result r = call("Mime/add_redirect", p, true);
        // Fallback: some older examples use source / destination / redirect_type.
        if (!r.ok && looksLikeParamError(r.message)) {
            Map<String, String> p2 = new LinkedHashMap<>();
            p2.put("domain", domain);
            p2.put("source", "/" + code);
            p2.put("destination", destination);
            p2.put("redirect_type", "temporary");
            p2.put("wildcard", "0");
            Result r2 = call("Mime/add_redirect", p2, true);
            if (r2.ok) return r2;
            r.message = r.message + " (Retry with alias params also failed: " + r2.message + ")";
        }
        return r;
    }

    private static boolean looksLikeParamError(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("redirect_type") || m.contains("parameter")
                || m.contains("invalid") || m.contains("unknown")
                || m.contains("required");
    }

    /** All redirects on the account; caller filters by domain. Newest first. */
    public List<Redirect> listRedirects() throws IOException, JSONException {
        Result r = call("Mime/list_redirects", new LinkedHashMap<String, String>(), false);
        if (!r.ok) throw new IOException(r.message);
        List<Redirect> out = new ArrayList<>();
        // r.json is the normalised result object (see call()).
        // Fall back to a nested "result" object if one is present.
        JSONObject res = r.json.optJSONObject("result");
        if (res == null) res = r.json;
        JSONArray data = res == null ? null : res.optJSONArray("data");
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i);
            if (o == null) continue;
            Redirect rd = new Redirect();
            rd.source = o.optString("source", o.optString("sourceurl", ""));
            rd.destination = o.optString("destination", o.optString("targeturl", ""));
            rd.type = o.optString("type", "");
            rd.statusCode = o.optString("statuscode", "");
            rd.domain = o.optString("domain", o.optString("urldomain", ""));
            if (rd.source.startsWith("/")) rd.code = rd.source.substring(1);
            else rd.code = rd.source;
            if (!rd.code.isEmpty()) out.add(rd);
        }
        // cPanel appends new redirects to the bottom of .htaccess, so the
        // list comes back oldest-first; reverse for newest-first display.
        java.util.Collections.reverse(out);
        return out;
    }

    /** Delete a redirect. Identified by domain + source path. */
    public Result deleteRedirect(String domain, String code) throws IOException, JSONException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("domain", domain);
        p.put("src", "/" + code);
        return call("Mime/delete_redirect", p, true);
    }

    /** Delete, retrying with the destination included if the first call fails. */
    public Result deleteRedirect(String domain, String code, String destination)
            throws IOException, JSONException {
        Result r = deleteRedirect(domain, code);
        if (!r.ok && destination != null && !destination.isEmpty()) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("domain", domain);
            p.put("src", "/" + code);
            p.put("args", "/" + code + " " + destination);
            Result r2 = call("Mime/delete_redirect", p, true);
            if (r2.ok) return r2;
        }
        return r;
    }

    // ---- .htaccess fix (Fileman) ----

    public static class HtaccessResult {
        public boolean ok;
        public boolean alreadyOk;
        public int moved;
        public String message = "";
    }

    /** Read a file from the cPanel account via Fileman/get_file_content. */
    public String getFileContent(String dir, String file)
            throws IOException, JSONException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("dir", dir);
        p.put("file", file);
        p.put("from_charset", "_DETECT_");
        p.put("to_charset", "_LOCALE_");
        Result r = call("Fileman/get_file_content", p, false);
        if (!r.ok) throw new IOException(r.message);
        Object data = r.json.opt("data");
        if (data instanceof String) return (String) data;
        if (data instanceof JSONObject) {
            String content = ((JSONObject) data).optString("content", "");
            if (!content.isEmpty() || ((JSONObject) data).has("content")) return content;
        }
        // Some servers return the content as a bare string in "data"
        // already handled above; anything else is unexpected.
        throw new IOException("Could not read " + file + ": unexpected reply.");
    }

    /** Save a file via Fileman/save_file_content. Returns the raw Result. */
    public Result saveFileContent(String dir, String file, String content)
            throws IOException, JSONException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("dir", dir);
        p.put("file", file);
        p.put("content", content);
        p.put("from_charset", "UTF-8");
        p.put("to_charset", "UTF-8");
        return call("Fileman/save_file_content", p, true);
    }

    /** True if a line is part of a cPanel redirect block. */
    private static boolean isRedirectLine(String line) {
        String t = line.trim();
        return t.startsWith("RewriteCond ") || t.startsWith("RewriteRule ")
                || t.startsWith("Redirect ") || t.startsWith("RedirectMatch ");
    }

    /** True if a redirect line mentions one of the known codes/destinations. */
    private static boolean mentionsRedirect(String line, List<Redirect> redirects) {
        String low = line.toLowerCase();
        // Any explicit 301/302 rewrite is a redirect, even if the code
        // is not in the list (e.g. /pay and /track, created long ago).
        if (low.contains("[r=301") || low.contains("[r=302")
                || low.contains(",r=301") || low.contains(",r=302")) return true;
        if (redirects != null) {
            for (Redirect rd : redirects) {
                if (rd.code != null && !rd.code.isEmpty()
                        && (low.contains("/" + rd.code.toLowerCase())
                        || low.contains("^" + rd.code.toLowerCase())
                        || low.contains(rd.code.toLowerCase() + "$"))) return true;
                if (rd.destination != null && !rd.destination.isEmpty()) {
                    String d = rd.destination.toLowerCase()
                            .replace("https://", "").replace("http://", "");
                    // Destination appears escaped in .htaccess, e.g.
                    // https\:\/\/google\.com\/ — match on the host part.
                    int slash = d.indexOf('/');
                    String host = slash >= 0 ? d.substring(0, slash) : d;
                    if (!host.isEmpty() && low.contains(host.replace(".", "\\."))) return true;
                    if (!host.isEmpty() && low.contains(host)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Move cPanel redirect blocks in public_html/.htaccess above
     * "# BEGIN WordPress", with a backup first. See the class comment.
     * The home directory is built from the cPanel username, which is
     * passed in from settings — it is not hard-coded here.
     */
    public HtaccessResult fixHtaccess()
            throws IOException, JSONException {
        HtaccessResult out = new HtaccessResult();
        String dir = "/home/" + user + "/public_html";
        String original;
        try {
            original = getFileContent(dir, ".htaccess");
        } catch (Exception e) {
            out.ok = false;
            out.message = "Could not read .htaccess: " + e.getMessage();
            return out;
        }
        if (original == null) original = "";
        // Known redirects, to identify which RewriteRules are ours.
        List<Redirect> redirects = null;
        try {
            redirects = listRedirects();
        } catch (Exception ignored) {
            redirects = null;
        }

        List<String> lines = new ArrayList<>();
        for (String l : original.split("\n", -1)) lines.add(l);
        int wpStart = -1, wpEnd = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("# BEGIN WordPress")) wpStart = i;
            if (wpStart >= 0 && lines.get(i).contains("# END WordPress")) {
                wpEnd = i;
                break;
            }
        }
        if (wpStart < 0) {
            out.ok = true;
            out.alreadyOk = true;
            out.message = "No WordPress block in .htaccess — links should already work.";
            return out;
        }
        if (wpEnd < 0) wpEnd = lines.size() - 1;

        // Split into: top (above WordPress), WordPress block, rest (below).
        List<String> top = new ArrayList<>(lines.subList(0, wpStart));
        List<String> wpBlock = new ArrayList<>(lines.subList(wpStart, wpEnd + 1));
        List<String> rest = new ArrayList<>(lines.subList(wpEnd + 1, lines.size()));

        // Find redirect groups in the part below WordPress. A group is
        // consecutive RewriteCond / RewriteRule / Redirect lines.
        List<List<String>> moved = new ArrayList<>();
        List<String> keptRest = new ArrayList<>();
        for (int i = 0; i < rest.size();) {
            String t = rest.get(i).trim();
            if (isRedirectLine(t)) {
                List<String> group = new ArrayList<>();
                boolean isOurs = false;
                while (i < rest.size() && isRedirectLine(rest.get(i).trim())) {
                    group.add(rest.get(i));
                    if (mentionsRedirect(rest.get(i), redirects)) isOurs = true;
                    i++;
                }
                if (isOurs) moved.add(group);
                else keptRest.addAll(group);
            } else {
                keptRest.add(rest.get(i));
                i++;
            }
        }
        if (moved.isEmpty()) {
            out.ok = true;
            out.alreadyOk = true;
            out.message = "Links already fixed: no redirect blocks below WordPress.";
            return out;
        }

        // Do not duplicate a block that is already above WordPress.
        String topJoined = String.join("\n", top);
        List<List<String>> toMove = new ArrayList<>();
        for (List<String> g : moved) {
            String joined = String.join("\n", g);
            if (!topJoined.contains(joined)) toMove.add(g);
        }
        if (toMove.isEmpty()) {
            out.ok = true;
            out.alreadyOk = true;
            out.message = "Links already fixed: redirects are already above WordPress.";
            return out;
        }

        // Build the new file: moved blocks first, then the old top,
        // then the WordPress block, then the rest without those blocks.
        StringBuilder nb = new StringBuilder();
        nb.append("# WeLiveThisWay Links — cPanel redirects moved above WordPress so short links fire first\n");
        for (List<String> g : toMove) {
            for (String l : g) nb.append(l).append("\n");
            nb.append("\n");
        }
        for (String l : top) nb.append(l).append("\n");
        for (String l : wpBlock) nb.append(l).append("\n");
        for (String l : keptRest) nb.append(l).append("\n");
        String updated = nb.toString();
        // Trim trailing blank lines to a single newline.
        while (updated.endsWith("\n\n")) updated = updated.substring(0, updated.length() - 1);

        // Backup first. Only proceed if the backup succeeds.
        Result backup = saveFileContent("public_html", ".htaccess.bak-wltw", original);
        if (!backup.ok) {
            out.ok = false;
            out.message = "Backup failed, .htaccess left unchanged: " + backup.message;
            return out;
        }

        // Save the reordered file. Do not retry blindly on failure.
        Result saved = saveFileContent("public_html", ".htaccess", updated);
        if (!saved.ok) {
            out.ok = false;
            out.message = "Could not save fixed .htaccess (backup is in .htaccess.bak-wltw): " + saved.message;
            return out;
        }

        // Re-read and verify the redirect blocks are now above WordPress.
        String check;
        try {
            check = getFileContent(dir, ".htaccess");
        } catch (Exception e) {
            out.ok = false;
            out.message = "Saved .htaccess but could not re-read it to verify: " + e.getMessage();
            return out;
        }
        int checkWp = check.indexOf("# BEGIN WordPress");
        int firstRule = -1;
        for (List<String> g : toMove) {
            for (String l : g) {
                int idx = check.indexOf(l.trim());
                if (idx >= 0 && (firstRule < 0 || idx < firstRule)) firstRule = idx;
            }
        }
        if (checkWp < 0 || firstRule < 0 || firstRule > checkWp) {
            out.ok = false;
            out.message = "Saved .htaccess but redirects are still not above WordPress. Backup is in .htaccess.bak-wltw.";
            return out;
        }
        out.ok = true;
        out.moved = toMove.size();
        out.message = "Fixed links: moved " + toMove.size()
                + " redirect block" + (toMove.size() == 1 ? "" : "s")
                + " above WordPress. Backup saved to .htaccess.bak-wltw.";
        return out;
    }
}
