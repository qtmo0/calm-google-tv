package com.ansub.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

// Talks to an LG webOS TV on the home network, the way LG's own phone app does: finds it, pairs once
// (the TV asks "Allow?"), then reads its HDMI inputs every few seconds and can switch to one.
// Only while the home screen is in front; nothing leaves the home network. No libraries: a small
// WebSocket client over TLS. The TV's certificate is self-signed, so it is pinned on first pairing
// (SHA-256 of the certificate) and every later connection must present the same one.
final class LgTv {

    static final String PREFS = "lgtv";
    private static final String TAG = "tvhome-lg";
    private static final int PORT = 3001, POLL_MS = 3000;

    // One HDMI input as the TV reports it, reduced to what the bar needs.
    static final class Input {
        String id;          // "HDMI_1"
        String label;       // the TV's name for it: "PS5 Spielkonsole"
        boolean on;         // a device is connected and powered
        boolean audio;      // a soundbar or amplifier (ARC/eARC): never a source to switch to
        boolean known;      // the TV knows a device there (set up, seen over CEC, or renamed)
        boolean game;       // the TV set it up as a game console
        String cecName;     // the name the device gives itself over HDMI-CEC, if it has spoken: "PlayStation 5"
    }

    interface Listener {
        // Called on the main thread. foreground is the TV's current app, like "com.webos.app.hdmi4".
        void onInputs(List<Input> inputs, String foreground);
        void onStatus(String status);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main;
    private final Listener listener;
    private volatile Thread worker;
    private volatile SSLSocket socket;
    private volatile String pendingSwitch;
    private final Object wake = new Object(); // lets a switch go out at once instead of at the next poll
    private int nextId;

    LgTv(Context c, Handler main, Listener l) {
        context = c.getApplicationContext();
        prefs = c.getSharedPreferences(PREFS, 0);
        this.main = main;
        listener = l;
    }

    boolean paired() { return prefs.getString("key", null) != null; }
    String model() { return prefs.getString("model", "LG-Fernseher"); }

    // Forgets the pairing here. The TV keeps its own entry until removed in its settings.
    void forget() {
        stop();
        prefs.edit().clear().apply();
    }

    // One LG TV that answered the search: where it is, its fixed id (survives a new IP address) and
    // what to call it in the menu ("TV · OLED65C43LA", from its device description).
    static final class Found {
        String host, uuid, name;
    }

    // Every LG TV on the network, found with SSDP as phone apps do. Blocking, about 4 s. UDP can get
    // lost and a TV doesn't always answer at once: ask three times, listen the whole time, skip
    // answers from other devices (a Hue bridge answers too) and duplicates.
    List<Found> discoverAll() {
        List<Found> out = new ArrayList<>();
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wm == null ? null : wm.createMulticastLock("tvhome-lg");
        try (DatagramSocket s = new DatagramSocket()) {
            if (lock != null) lock.acquire();
            byte[] msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                    + "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            long end = System.currentTimeMillis() + 4000, nextAsk = 0;
            int asked = 0;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                if (asked < 3 && System.currentTimeMillis() >= nextAsk) {
                    s.send(new DatagramPacket(msg, msg.length, group, 1900));
                    asked++;
                    nextAsk = System.currentTimeMillis() + 800;
                }
                s.setSoTimeout(400);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (java.net.SocketTimeoutException e) { continue; }
                String reply = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
                if (!reply.contains("webos-second-screen")) continue;
                String host = p.getAddress().getHostAddress();
                boolean dup = false;
                for (Found f : out) if (f.host.equals(host)) dup = true;
                if (dup) continue;
                Found f = new Found();
                f.host = host;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)USN:\\s*uuid:([0-9a-f-]+)").matcher(reply);
                f.uuid = m.find() ? m.group(1) : null;
                out.add(f);
            }
        } catch (Exception e) {
            Log.i(TAG, "search failed: " + e);
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
        }
        for (Found f : out) {
            String[] d = describe(f.host);
            f.name = d == null ? "LG-Fernseher" : d[0];
            if (f.uuid == null && d != null) f.uuid = d[1];
        }
        // Some LG TVs stop answering the search after a while (seen on an OLED C4, ports still open).
        // Then look for the TV's control port on the home network itself.
        if (out.isEmpty()) out = scanHomeNetwork();
        if (out.isEmpty()) Log.i(TAG, "no LG TV found");
        return out;
    }

    // Fallback search: every address of this device's own /24 network, port 3001 (the TV's control
    // port), confirmed by its device description saying "LG Electronics". Local only, about 2 s.
    private List<Found> scanHomeNetwork() {
        final List<Found> out = java.util.Collections.synchronizedList(new ArrayList<>());
        String me = ownAddress();
        if (me == null) return out;
        String prefix = me.substring(0, me.lastIndexOf('.') + 1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(32);
        for (int i = 1; i < 255; i++) {
            final String host = prefix + i;
            if (host.equals(me)) continue;
            pool.execute(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, PORT), 400);
                } catch (Exception e) {
                    return;
                }
                String[] d = describe(host);
                if (d == null || !d[2].contains("LG")) return;
                Found f = new Found();
                f.host = host;
                f.name = d[0];
                f.uuid = d[1];
                out.add(f);
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(8, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        List<Found> sorted = new ArrayList<>(out);
        java.util.Collections.sort(sorted, (a, b) -> a.host.compareTo(b.host));
        if (!sorted.isEmpty()) Log.i(TAG, "found by scanning the home network: " + sorted.size());
        return sorted;
    }

    // This device's IPv4 address on the home network: Wi-Fi or Ethernet, never a VPN (with Tailscale on,
    // the VPN counts as the "active" network). Null if there is none.
    @SuppressWarnings("deprecation")
    private String ownAddress() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null || c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (!c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        && !c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
                android.net.LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (android.net.LinkAddress a : lp.getLinkAddresses())
                    if (a.getAddress() instanceof java.net.Inet4Address && a.getPrefixLength() >= 16)
                        return a.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // The first LG TV found, or the one with this id if given (after its address changed). Or null.
    Found discover(String uuid) {
        for (Found f : discoverAll()) if (uuid == null || uuid.equals(f.uuid)) return f;
        return null;
    }

    // Pairings from before ids were kept: only if there is exactly one LG TV, it must be that one.
    private Found onlyOne() {
        List<Found> all = discoverAll();
        return all.size() == 1 ? all.get(0) : null;
    }

    // From the TV's device description (plain HTTP on port 1954, read with a socket):
    // {"TV · OLED65C43LA" (name set on the TV and model), its id, manufacturer}. Null if unreadable.
    private static String[] describe(String host) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, 1954), 1500);
            s.setSoTimeout(1500);
            OutputStream out = s.getOutputStream();
            out.write(("GET / HTTP/1.0\r\nHost: " + host + ":1954\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            int n;
            while (b.size() < 64_000 && (n = in.read(chunk)) > 0) b.write(chunk, 0, n);
            String xml = b.toString("UTF-8");
            String name = tag(xml, "friendlyName"), model = tag(xml, "modelNumber");
            String udn = tag(xml, "UDN"), maker = tag(xml, "manufacturer");
            if (model != null) model = model.split("\\.")[0];
            if (name == null || name.isEmpty()) name = "LG-Fernseher";
            String label = model == null || model.isEmpty() ? name : name + " · " + model;
            return new String[] {label, udn == null ? null : udn.replace("uuid:", ""), maker == null ? "" : maker};
        } catch (Exception e) {
            return null;
        }
    }

    private static String tag(String xml, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    // Connects and keeps reading the inputs until stop(). pair = true may show the TV's "Allow?"
    // prompt; false only uses an existing pairing and never prompts.
    void start(final boolean pair) { start(pair, null); }

    // hint: the TV picked in the menu, so pairing goes to exactly that one without searching again.
    void start(final boolean pair, final Found hint) {
        if (worker != null) return;
        Thread t = new Thread(() -> run(pair, hint), "lgtv");
        worker = t;
        t.start();
    }

    void stop() {
        Thread t = worker;
        worker = null;
        closeQuietly();
        if (t != null) t.interrupt();
    }

    boolean running() { return worker != null; }

    // Switches the TV to an input; connects first if needed. The worker thread sends it.
    void switchTo(String inputId) {
        pendingSwitch = inputId;
        if (worker == null) start(false);
        synchronized (wake) { wake.notifyAll(); }
    }

    private void flushSwitch() { // worker thread only
        String id = pendingSwitch;
        if (id == null || socket == null) return;
        pendingSwitch = null;
        Log.i(TAG, "switching TV to " + id);
        try {
            JSONObject r = ask("ssap://tv/switchInput", new JSONObject().put("inputId", id));
            Log.i(TAG, "TV answered: " + (r == null ? "refused" : r.toString()));
        } catch (Exception e) {
            Log.w(TAG, "switch failed", e);
        }
    }

    private void run(boolean pair, Found hint) {
        try {
            String host = hint != null ? hint.host : prefs.getString("host", null);
            String uuid = hint != null ? hint.uuid : prefs.getString("uuid", null);
            if (host == null) {
                Found f = discover(uuid);
                host = f == null ? null : f.host;
            }
            if (host == null) { status(pair ? "Kein LG-Fernseher im Netz gefunden" : null); return; }
            try {
                open(host);
            } catch (SecurityException e) {
                throw e;
            } catch (Exception e) { // the TV may have a new address: look for this same TV once (by its id)
                Found f = uuid != null ? discover(uuid) : onlyOne();
                if (f == null || f.host.equals(host)) throw e;
                host = f.host;
                uuid = f.uuid;
                open(host);
                prefs.edit().putString("host", host).putString("uuid", uuid).apply();
            }
            String key = prefs.getString("key", null);
            if (key == null && !pair) return; // never prompt unasked
            send(register(key));
            if (pair) status("Bitte am Fernseher bestätigen");
            socket.setSoTimeout(pair ? 90_000 : 15_000);
            while (true) {
                JSONObject m = read();
                String type = m.optString("type");
                if (pair) Log.i(TAG, "pairing: " + type + " " + String.valueOf(m.opt("payload")).replaceAll("client-key\\\":\\\"[^\\\"]+", "client-key\\\":\\\"…"));
                if ("registered".equals(type)) {
                    prefs.edit().putString("key", m.getJSONObject("payload").getString("client-key"))
                            .putString("host", host).putString("uuid", uuid).apply();
                    break;
                }
                if ("error".equals(type)) { Log.i(TAG, "pairing refused: " + m.optString("error")); status("Verbindung abgelehnt"); return; }
            }
            readModel();
            if (pair) status("Verbunden mit " + model());
            socket.setSoTimeout(POLL_MS * 3);
            flushSwitch();
            while (worker == Thread.currentThread()) {
                List<Input> inputs = parseInputs(ask("ssap://tv/getExternalInputList", null));
                JSONObject fg = ask("ssap://com.webos.applicationManager/getForegroundAppInfo", null);
                final String foreground = fg == null ? null : fg.optString("appId", null);
                final List<Input> out = inputs;
                if (out != null) main.post(() -> listener.onInputs(out, foreground));
                flushSwitch();
                synchronized (wake) { if (pendingSwitch == null) wake.wait(POLL_MS); }
                if (Thread.interrupted()) break;
            }
        } catch (InterruptedException ignored) {
        } catch (SecurityException e) {
            Log.w(TAG, e.getMessage());
            status("Fernseher meldet sich mit einem anderen Zertifikat – bitte neu verbinden");
        } catch (Exception e) {
            Log.i(TAG, "connection ended: " + e);
            if (pair) status("Verbindung fehlgeschlagen");
        } finally {
            closeQuietly();
            if (worker == Thread.currentThread()) worker = null;
        }
    }

    // The model name for the menu ("LG OLED65C4"), if the TV tells it; not needed for anything else.
    private void readModel() {
        try {
            JSONObject info = ask("ssap://system/getSystemInfo", null);
            String m = info == null ? null : info.optString("modelName", null);
            if (m != null && !m.isEmpty()) prefs.edit().putString("model", "LG " + m.split("\\.")[0]).apply();
        } catch (Exception ignored) {}
    }

    private void status(final String s) {
        if (s != null) main.post(() -> listener.onStatus(s));
    }

    // ---- the TV's answer, reduced to what the bar needs ----------------------------------------

    static List<Input> parseInputs(JSONObject payload) {
        if (payload == null) return null;
        JSONArray devices = payload.optJSONArray("devices");
        if (devices == null) return null;
        List<Input> out = new ArrayList<>();
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null || !d.optString("id").startsWith("HDMI")) continue;
            Input in = new Input();
            in.id = d.optString("id");
            in.label = d.optString("label", in.id.replace('_', ' '));
            in.on = d.optBoolean("connected");
            in.known = in.on || d.optBoolean("modified");
            JSONArray sub = d.optJSONArray("subList");
            for (int j = 0; sub != null && j < sub.length(); j++) {
                JSONObject s = sub.optJSONObject(j);
                if (s == null) continue;
                in.known = true; // a device set up on the TV, or one seen over HDMI-CEC
                if ("SIMPLINK".equals(s.optString("id"))) {
                    in.cecName = s.optString("osdName", null);
                    // A console going to rest stays "connected" for a while; its own CEC power state
                    // says "Off" at once, so that wins.
                    if ("Off".equals(s.optString("cecPower"))) in.on = false;
                }
                String svc = s.optString("serviceType");
                if ("audio".equals(svc) || "ARC".equals(s.optString("connectedInput")) || s.optInt("cecpDevType") == 5)
                    in.audio = true;
                if ("game".equals(svc)) in.game = true;
            }
            out.add(in);
        }
        return out;
    }

    // ---- SSAP messages -------------------------------------------------------------------------

    private JSONObject register(String key) throws Exception {
        JSONObject manifest = new JSONObject()
                .put("manifestVersion", 1).put("appVersion", "1.0")
                .put("signed", new JSONObject().put("appId", "calm-google-tv").put("vendorId", "calm-google-tv")
                        .put("localizedAppNames", new JSONObject().put("", "Calm Google TV")))
                .put("permissions", new JSONArray().put("READ_INPUT_DEVICE_LIST").put("READ_RUNNING_APPS")
                        .put("READ_INSTALLED_APPS").put("LAUNCH").put("CONTROL_INPUT_TV"));
        JSONObject payload = new JSONObject().put("forcePairing", false).put("pairingType", "PROMPT").put("manifest", manifest);
        if (key != null) payload.put("client-key", key);
        return new JSONObject().put("type", "register").put("id", "reg").put("payload", payload);
    }

    private JSONObject request(String uri, JSONObject payload) throws Exception {
        JSONObject m = new JSONObject().put("type", "request").put("id", "r" + (++nextId)).put("uri", uri);
        if (payload != null) m.put("payload", payload);
        return m;
    }

    // Sends a request and waits for its answer; returns the payload, or null if the TV said no.
    private JSONObject ask(String uri, JSONObject payload) throws Exception {
        JSONObject req = request(uri, payload);
        send(req);
        String id = req.getString("id");
        while (true) {
            JSONObject m = read();
            if (!id.equals(m.optString("id"))) continue;
            return "response".equals(m.optString("type")) ? m.optJSONObject("payload") : null;
        }
    }

    // ---- a minimal WebSocket client over TLS ---------------------------------------------------

    private void open(String host) throws Exception {
        final X509Certificate[] seen = new X509Certificate[1];
        TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) { seen[0] = c[0]; }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {tm}, new SecureRandom());
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, PORT), 5000);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, PORT, true);
        s.setSoTimeout(15_000);
        s.startHandshake();
        // Pin the TV's certificate: the first one seen is trusted, later ones must match it.
        String pin = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(seen[0].getEncoded()), Base64.NO_WRAP);
        String known = prefs.getString("pin", null);
        if (known == null) prefs.edit().putString("pin", pin).apply();
        else if (!known.equals(pin)) { s.close(); throw new SecurityException("LG certificate changed"); }
        socket = s;

        byte[] k = new byte[16];
        new SecureRandom().nextBytes(k);
        String req = "GET / HTTP/1.1\r\nHost: " + host + ":" + PORT + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + Base64.encodeToString(k, Base64.NO_WRAP) + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int c = in.read();
            if (c < 0) throw new java.io.IOException("closed during handshake");
            head.append((char) c);
        }
        if (!head.toString().startsWith("HTTP/1.1 101")) throw new java.io.IOException("no websocket");
    }

    private void send(JSONObject msg) throws Exception {
        sendFrame(0x1, msg.toString().getBytes(StandardCharsets.UTF_8));
    }

    // One masked frame, as clients must send them. op 0x1 = text, 0xA = pong.
    private synchronized void sendFrame(int op, byte[] data) throws Exception {
        SSLSocket s = socket;
        if (s == null) throw new java.io.IOException("not connected");
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
        f.write(0x80 | op);
        if (data.length < 126) f.write(0x80 | data.length);
        else { f.write(0x80 | 126); f.write(data.length >> 8); f.write(data.length & 0xFF); }
        f.write(mask);
        for (int i = 0; i < data.length; i++) f.write(data[i] ^ mask[i % 4]);
        OutputStream out = s.getOutputStream();
        out.write(f.toByteArray());
        out.flush();
    }

    private JSONObject read() throws Exception {
        InputStream in = socket.getInputStream();
        while (true) {
            int b1 = readByte(in), b2 = readByte(in);
            int op = b1 & 0x0F;
            long n = b2 & 0x7F;
            if (n == 126) n = (readByte(in) << 8) | readByte(in);
            else if (n == 127) { n = 0; for (int i = 0; i < 8; i++) n = (n << 8) | readByte(in); }
            byte[] m = null;
            if ((b2 & 0x80) != 0) { m = new byte[4]; readFully(in, m); }
            byte[] p = new byte[(int) n];
            readFully(in, p);
            if (m != null) for (int i = 0; i < p.length; i++) p[i] ^= m[i % 4];
            if (op == 0x8) throw new java.io.IOException("closed by TV" + closeReason(p));
            if (op == 0x9) { sendFrame(0xA, p); continue; } // the TV checks we're still there: answer it
            if (op == 0xA) continue;
            return new JSONObject(new String(p, StandardCharsets.UTF_8));
        }
    }

    private static String closeReason(byte[] p) {
        if (p.length < 2) return "";
        return " (" + (((p[0] & 0xFF) << 8) | (p[1] & 0xFF)) + " " + new String(p, 2, p.length - 2, StandardCharsets.UTF_8) + ")";
    }

    private static int readByte(InputStream in) throws java.io.IOException {
        int b = in.read();
        if (b < 0) throw new java.io.IOException("closed");
        return b;
    }

    private static void readFully(InputStream in, byte[] b) throws java.io.IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) throw new java.io.IOException("closed");
            off += r;
        }
    }

    private void closeQuietly() {
        SSLSocket s = socket;
        socket = null;
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }
}
