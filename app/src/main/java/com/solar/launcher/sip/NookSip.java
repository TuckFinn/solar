package com.solar.launcher.sip;

import android.net.rtp.AudioCodec;
import android.net.rtp.AudioGroup;
import android.net.rtp.AudioStream;
import android.net.rtp.RtpStream;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 2026-11-03 — A small SIP user agent for nookLine (Asterisk 20 on familynook,
 * Tailscale-only, plain UDP, digest auth). The Y1's ROM has no SipService, so
 * Solar carries its own: REGISTER, one call at a time — placed (INVITE / ACK / BYE)
 * or, since 2026-11-04, received (a walkie page: 180 Ringing, then 200 with an SDP
 * answer, or 603) — and answers to whatever Asterisk sends mid-call. Media is
 * Android's own android.net.rtp (librtp_jni: G.711 / GSM, echo suppression), joined
 * to an AudioGroup whose mode is the push-to-talk switch: MUTED while listening,
 * ECHO_SUPPRESSION while the button is held.
 *
 * Deliberately narrow: one registrar, UDP only, no TLS/SRTP. Not thread-safe: one
 * thread (WalkieService's worker) owns it.
 */
public final class NookSip {
    private static final String TAG = "NookSip";

    public interface Listener {
        void onState(String state);          // "registered", "in call", "ended", "error: ..."
        /** A call is ringing here (a walkie page); answer() or decline(). */
        void onIncoming(String from);
        /** The ringing call went away before it was answered. */
        void onIncomingGone();
    }

    private final String server;            // e.g. 100.126.143.83
    private final int port;
    private final String user;
    private final String password;
    private final SecureRandom rnd = new SecureRandom();
    private Listener listener;

    private DatagramSocket sock;
    private InetAddress serverAddr;
    private String localIp;
    private int localPort;
    private int cseq = 1;
    private final String regCallId;
    private final String regTag;

    // the call (either direction): dialog identity as this side writes it
    private String callId, dlgLocal, dlgRemote, remoteTarget, calleeUri;
    private int callCseq;
    private AudioStream audio;
    private AudioGroup group;
    // a ringing incoming call
    private Msg incoming;
    private String incomingTag;

    public NookSip(String server, int port, String user, String password) {
        this.server = server;
        this.port = port;
        this.user = user;
        this.password = password;
        this.regCallId = hex(12) + "@solar";
        this.regTag = hex(6);
    }

    public void setListener(Listener l) { listener = l; }

    private void state(String s) {
        Log.i(TAG, s);
        if (listener != null) listener.onState(s);
    }

    // ---- transport -------------------------------------------------------------

    private void open() throws Exception {
        if (sock != null) return;
        serverAddr = InetAddress.getByName(server);
        // The address the Y1 reaches the PBX from (its tailnet IP): what goes in
        // Via, Contact and the SDP, so Asterisk's answers come back to us.
        DatagramSocket probe = new DatagramSocket();
        probe.connect(serverAddr, port);
        localIp = probe.getLocalAddress().getHostAddress();
        probe.close();
        sock = new DatagramSocket(0, InetAddress.getByName(localIp));
        localPort = sock.getLocalPort();
    }

    /**
     * 2026-11-04 — the route to the PBX can change under us: at boot Solar starts before
     * Tailscale, so the first probe answers with the Wi-Fi address and the socket stayed
     * bound to it — every REGISTER then left with a source the PBX drops, until Solar
     * restarted (2Y1 never registered after a reboot). Re-probe before each REGISTER and
     * rebind when the address changed, unless a call or a page is in progress.
     */
    private void refreshTransport() {
        if (sock == null || callId != null || incoming != null) return;
        try {
            DatagramSocket probe = new DatagramSocket();
            probe.connect(serverAddr, port);
            String now = probe.getLocalAddress().getHostAddress();
            probe.close();
            if (now != null && !now.equals(localIp) && !"0.0.0.0".equals(now)) {
                Log.i(TAG, "route to the PBX now from " + now + " (was " + localIp + "); rebinding");
                sock.close();
                sock = null;
                open();
            }
        } catch (Exception e) {
            Log.w(TAG, "route check: " + e.getMessage());
        }
    }

    public void close() {
        hangup();
        if (incoming != null) {
            try { decline(); } catch (Exception ignored) {}
        }
        if (sock != null) sock.close();
        sock = null;
    }

    private void send(String msg) throws Exception {
        byte[] b = msg.getBytes("UTF-8");
        sock.send(new DatagramPacket(b, b.length, serverAddr, port));
    }

    private String contact() {
        return "<sip:" + user + "@" + localIp + ":" + localPort + ">";
    }

    /** Next SIP response, or null on timeout. Requests are handled here as they come. */
    private Msg receive(int timeoutMs) throws Exception {
        long until = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[8192];
        while (true) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) return null;
            sock.setSoTimeout((int) left);
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                sock.receive(p);
            } catch (SocketTimeoutException e) {
                return null;
            }
            Msg m = Msg.parse(new String(p.getData(), 0, p.getLength(), "UTF-8"));
            if (m == null) continue;
            if (m.isRequest()) {
                handleRequest(m);
                continue;
            }
            return m;
        }
    }

    private void handleRequest(Msg req) throws Exception {
        String cid = req.header("Call-ID");
        boolean ours = callId != null && callId.equals(cid);
        boolean ringing = incoming != null && cid != null && cid.equals(incoming.header("Call-ID"));
        if ("ACK".equals(req.method)) return;                       // never answered
        if ("INVITE".equals(req.method)) {
            if (ours) {                                             // re-INVITE / session refresh
                respond(req, 200, "OK", null, sdp(audio != null ? audio.getLocalPort() : 0));
            } else if (ringing) {                                   // retransmission
                respond(req, 180, "Ringing", incomingTag, null);
            } else if (callId != null || incoming != null) {
                respond(req, 486, "Busy Here", hex(6), null);
            } else {
                incoming = req;
                incomingTag = hex(6);
                respond(req, 100, "Trying", null, null);
                respond(req, 180, "Ringing", incomingTag, null);
                String from = req.header("From");
                String name = from != null && from.startsWith("\"") ? between(from, "\"", "\"") : null;
                state("ringing");
                if (listener != null) listener.onIncoming(name != null ? name : from);
            }
            return;
        }
        if ("CANCEL".equals(req.method)) {
            respond(req, 200, "OK", null, null);
            if (ringing) {
                Msg inv = incoming;
                incoming = null;
                respond(inv, 487, "Request Terminated", incomingTag, null);
                state("page withdrawn");
                if (listener != null) listener.onIncomingGone();
            }
            return;
        }
        respond(req, 200, "OK", null, null);                        // BYE, OPTIONS, NOTIFY, UPDATE...
        if ("BYE".equals(req.method) && ours) {
            stopMedia();
            callId = null;
            state("ended (remote hung up)");
        }
    }

    /** A response to {@code req}; {@code toTag} is added when its To has none. */
    private void respond(Msg req, int code, String reason, String toTag, String body) throws Exception {
        StringBuilder r = new StringBuilder("SIP/2.0 ").append(code).append(' ').append(reason).append("\r\n");
        for (String v : req.all("Via")) r.append("Via: ").append(v).append("\r\n");
        r.append("From: ").append(req.header("From")).append("\r\n");
        String to = req.header("To");
        if (to != null && !to.contains("tag=") && code > 100) to += ";tag=" + (toTag != null ? toTag : hex(6));
        r.append("To: ").append(to).append("\r\n");
        r.append("Call-ID: ").append(req.header("Call-ID")).append("\r\n");
        r.append("CSeq: ").append(req.header("CSeq")).append("\r\n");
        if (code < 300 && code > 100) r.append("Contact: ").append(contact()).append("\r\n");
        r.append("User-Agent: Solar (Innioasis Y1)\r\n");
        if (body != null) {
            r.append("Content-Type: application/sdp\r\n");
            r.append("Content-Length: ").append(body.getBytes("UTF-8").length).append("\r\n\r\n").append(body);
        } else {
            r.append("Content-Length: 0\r\n\r\n");
        }
        send(r.toString());
    }

    // ---- registration ----------------------------------------------------------

    /** REGISTER (expires seconds; 0 unregisters). True on 200 OK. */
    public boolean register(int expires) throws Exception {
        open();
        refreshTransport();
        String uri = "sip:" + server;
        String auth = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            int n = cseq++;
            StringBuilder m = new StringBuilder();
            m.append("REGISTER ").append(uri).append(" SIP/2.0\r\n");
            m.append(via());
            m.append("Max-Forwards: 70\r\n");
            m.append("From: <sip:").append(user).append('@').append(server).append(">;tag=").append(regTag).append("\r\n");
            m.append("To: <sip:").append(user).append('@').append(server).append(">\r\n");
            m.append("Call-ID: ").append(regCallId).append("\r\n");
            m.append("CSeq: ").append(n).append(" REGISTER\r\n");
            m.append("Contact: ").append(contact()).append("\r\n");
            m.append("Expires: ").append(expires).append("\r\n");
            if (auth != null) m.append("Authorization: ").append(auth).append("\r\n");
            m.append("User-Agent: Solar (Innioasis Y1)\r\n");
            m.append("Content-Length: 0\r\n\r\n");
            send(m.toString());
            Msg r = finalResponse(4000);
            if (r == null) { state("error: no answer from " + server); return false; }
            if (r.code == 200) { state(expires == 0 ? "unregistered" : "registered as " + user); return true; }
            if ((r.code == 401 || r.code == 407) && auth == null) {
                auth = digest(r, "REGISTER", uri);
                continue;
            }
            state("error: REGISTER " + r.code + " " + r.reason);
            return false;
        }
        return false;
    }

    private Msg finalResponse(int timeoutMs) throws Exception {
        long until = System.currentTimeMillis() + timeoutMs;
        while (true) {
            int left = (int) (until - System.currentTimeMillis());
            if (left <= 0) return null;
            Msg r = receive(left);
            if (r == null) return null;
            if (r.code >= 200) return r;
        }
    }

    // ---- placing a call ---------------------------------------------------------

    /**
     * Call {@code ext} (7000 = the walkie room). Joins media with the mic muted;
     * {@link #talk(boolean)} opens it. True once answered.
     */
    public boolean call(String ext) throws Exception {
        open();
        if (callId != null) return true;
        audio = new AudioStream(InetAddress.getByName(localIp));
        audio.setMode(RtpStream.MODE_NORMAL);
        String uri = "sip:" + ext + "@" + server;
        calleeUri = uri;
        callId = hex(12) + "@solar";
        String fromTag = hex(6);
        String auth = null;
        String offer = sdp(audio.getLocalPort());
        for (int attempt = 0; attempt < 3; attempt++) {
            callCseq = cseq++;
            String branch = "z9hG4bK" + hex(8);
            StringBuilder m = new StringBuilder();
            m.append("INVITE ").append(uri).append(" SIP/2.0\r\n");
            m.append("Via: SIP/2.0/UDP ").append(localIp).append(':').append(localPort).append(";branch=").append(branch).append(";rport\r\n");
            m.append("Max-Forwards: 70\r\n");
            m.append("From: <sip:").append(user).append('@').append(server).append(">;tag=").append(fromTag).append("\r\n");
            m.append("To: <").append(uri).append(">\r\n");
            m.append("Call-ID: ").append(callId).append("\r\n");
            m.append("CSeq: ").append(callCseq).append(" INVITE\r\n");
            m.append("Contact: ").append(contact()).append("\r\n");
            if (auth != null) m.append("Authorization: ").append(auth).append("\r\n");
            m.append("User-Agent: Solar (Innioasis Y1)\r\n");
            m.append("Content-Type: application/sdp\r\n");
            m.append("Content-Length: ").append(offer.getBytes("UTF-8").length).append("\r\n\r\n");
            m.append(offer);
            send(m.toString());
            Msg r = finalResponse(15000);
            if (r == null) { state("error: INVITE timed out"); endCallLocal(); return false; }
            if ((r.code == 401 || r.code == 407) && auth == null) {
                ackFinal(r, uri, branch, fromTag);          // a final response to INVITE is ACKed
                auth = digest(r, "INVITE", uri);
                continue;
            }
            if (r.code != 200) {
                ackFinal(r, uri, branch, fromTag);
                state("error: INVITE " + r.code + " " + r.reason);
                endCallLocal();
                return false;
            }
            dlgLocal = "<sip:" + user + "@" + server + ">;tag=" + fromTag;
            dlgRemote = r.header("To");
            String c = r.header("Contact");
            remoteTarget = c != null && between(c, "<", ">") != null ? between(c, "<", ">") : uri;
            ack2xx();
            if (!startMedia(r.body)) {
                hangup();
                state("error: no usable audio in the answer");
                return false;
            }
            state("in call " + ext);
            return true;
        }
        endCallLocal();
        return false;
    }

    // ---- receiving a call (a walkie page) --------------------------------------

    public boolean ringing() { return incoming != null; }

    /** Answer the ringing call: join its media (mic muted), 200 OK with our SDP. */
    public boolean answer() throws Exception {
        Msg inv = incoming;
        if (inv == null || callId != null) return false;
        incoming = null;
        audio = new AudioStream(InetAddress.getByName(localIp));
        audio.setMode(RtpStream.MODE_NORMAL);
        if (!startMedia(inv.body)) {
            respond(inv, 488, "Not Acceptable Here", incomingTag, null);
            stopMedia();
            state("error: the page offered no usable audio");
            return false;
        }
        callId = inv.header("Call-ID");
        String to = inv.header("To");
        dlgLocal = to != null && !to.contains("tag=") ? to + ";tag=" + incomingTag : to;
        dlgRemote = inv.header("From");
        String c = inv.header("Contact");
        remoteTarget = c != null && between(c, "<", ">") != null ? between(c, "<", ">") : "sip:" + server;
        calleeUri = remoteTarget;
        respond(inv, 200, "OK", incomingTag, sdp(audio.getLocalPort()));
        state("in call (answered)");
        return true;
    }

    /** Turn the ringing call away. */
    public void decline() throws Exception {
        Msg inv = incoming;
        incoming = null;
        if (inv != null) respond(inv, 603, "Decline", incomingTag, null);
        state("page declined");
    }

    // ---- in a call -------------------------------------------------------------

    /** Push-to-talk: the mic goes out only while {@code on}. */
    public void talk(boolean on) {
        if (group != null) group.setMode(on ? AudioGroup.MODE_ECHO_SUPPRESSION : AudioGroup.MODE_MUTED);
    }

    public boolean inCall() { return callId != null; }

    /** Pump the socket for {@code ms}, handling whatever Asterisk sends. */
    public void service(int ms) throws Exception {
        if (sock != null) receive(ms);
    }

    public void hangup() {
        if (callId == null) return;
        try {
            int n = cseq++;
            StringBuilder m = new StringBuilder();
            m.append("BYE ").append(remoteTarget).append(" SIP/2.0\r\n");
            m.append(via());
            m.append("Max-Forwards: 70\r\n");
            m.append("From: ").append(dlgLocal).append("\r\n");
            m.append("To: ").append(dlgRemote).append("\r\n");
            m.append("Call-ID: ").append(callId).append("\r\n");
            m.append("CSeq: ").append(n).append(" BYE\r\n");
            m.append("Content-Length: 0\r\n\r\n");
            send(m.toString());
            finalResponse(3000);
        } catch (Exception e) {
            Log.w(TAG, "BYE failed: " + e.getMessage());
        }
        endCallLocal();
        state("ended");
    }

    private void endCallLocal() {
        stopMedia();
        callId = null;
    }

    private void ackFinal(Msg resp, String uri, String branch, String fromTag) throws Exception {
        StringBuilder m = new StringBuilder();
        m.append("ACK ").append(uri).append(" SIP/2.0\r\n");
        m.append("Via: SIP/2.0/UDP ").append(localIp).append(':').append(localPort).append(";branch=").append(branch).append(";rport\r\n");
        m.append("Max-Forwards: 70\r\n");
        m.append("From: <sip:").append(user).append('@').append(server).append(">;tag=").append(fromTag).append("\r\n");
        m.append("To: ").append(resp.header("To")).append("\r\n");
        m.append("Call-ID: ").append(callId).append("\r\n");
        m.append("CSeq: ").append(callCseq).append(" ACK\r\n");
        m.append("Content-Length: 0\r\n\r\n");
        send(m.toString());
    }

    private void ack2xx() throws Exception {
        StringBuilder m = new StringBuilder();
        m.append("ACK ").append(remoteTarget).append(" SIP/2.0\r\n");
        m.append(via());
        m.append("Max-Forwards: 70\r\n");
        m.append("From: ").append(dlgLocal).append("\r\n");
        m.append("To: ").append(dlgRemote).append("\r\n");
        m.append("Call-ID: ").append(callId).append("\r\n");
        m.append("CSeq: ").append(callCseq).append(" ACK\r\n");
        m.append("Content-Length: 0\r\n\r\n");
        send(m.toString());
    }

    // ---- media -----------------------------------------------------------------

    private String sdp(int rtpPort) {
        long id = System.currentTimeMillis() / 1000;
        return "v=0\r\n"
                + "o=solar " + id + " " + id + " IN IP4 " + localIp + "\r\n"
                + "s=Solar\r\n"
                + "c=IN IP4 " + localIp + "\r\n"
                + "t=0 0\r\n"
                + "m=audio " + rtpPort + " RTP/AVP 0 8 3 101\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n"
                + "a=rtpmap:8 PCMA/8000\r\n"
                + "a=rtpmap:3 GSM/8000\r\n"
                + "a=rtpmap:101 telephone-event/8000\r\n"
                + "a=fmtp:101 0-15\r\n"
                + "a=ptime:20\r\n"
                + "a=sendrecv\r\n";
    }

    /** Connect our AudioStream to the far end's SDP (an answer or an offer). */
    private boolean startMedia(String sdp) {
        try {
            String ip = null;
            int rport = 0, pt = -1;
            for (String line : sdp.split("\r?\n")) {
                if (line.startsWith("c=IN IP4 ")) ip = line.substring(9).trim();
                if (line.startsWith("m=audio ")) {
                    String[] f = line.trim().split(" ");
                    rport = Integer.parseInt(f[1]);
                    for (int i = 3; i < f.length; i++) {
                        int p = Integer.parseInt(f[i].trim());
                        if (p == 0 || p == 8 || p == 3) { pt = p; break; }
                    }
                }
            }
            if (ip == null || rport == 0 || pt < 0) return false;
            AudioCodec codec = pt == 0 ? AudioCodec.PCMU : pt == 8 ? AudioCodec.PCMA : AudioCodec.GSM;
            audio.setCodec(codec);
            audio.associate(InetAddress.getByName(ip), rport);
            group = new AudioGroup();
            group.setMode(AudioGroup.MODE_MUTED);       // listening until the button is held
            audio.join(group);
            Log.i(TAG, "media " + codec.rtpmap + " -> " + ip + ":" + rport + " from :" + audio.getLocalPort());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "media failed", e);
            return false;
        }
    }

    private void stopMedia() {
        try { if (audio != null) audio.join(null); } catch (Exception ignored) {}
        try { if (group != null) group.clear(); } catch (Exception ignored) {}
        try { if (audio != null) audio.release(); } catch (Exception ignored) {}
        audio = null;
        group = null;
    }

    // ---- digest auth (RFC 2617, qop=auth or none) -----------------------------

    private String digest(Msg challenge, String method, String uri) throws Exception {
        String h = challenge.header(challenge.code == 407 ? "Proxy-Authenticate" : "WWW-Authenticate");
        if (h == null) return null;
        Map<String, String> p = authParams(h);
        String realm = p.get("realm"), nonce = p.get("nonce"), qop = p.get("qop"), opaque = p.get("opaque");
        String ha1 = md5(user + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        StringBuilder a = new StringBuilder("Digest username=\"").append(user).append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(uri).append("\", algorithm=MD5");
        if (qop != null && qop.contains("auth")) {
            String cnonce = hex(8), nc = "00000001";
            String resp = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
            a.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append("\", response=\"").append(resp).append('"');
        } else {
            a.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append('"');
        }
        if (opaque != null) a.append(", opaque=\"").append(opaque).append('"');
        return a.toString();
    }

    static Map<String, String> authParams(String h) {
        Map<String, String> m = new HashMap<String, String>();
        String s = h.replaceFirst("(?i)^\\s*Digest\\s+", "");
        java.util.regex.Matcher mm = java.util.regex.Pattern
                .compile("(\\w+)\\s*=\\s*(\"([^\"]*)\"|([^,\\s]+))").matcher(s);
        while (mm.find()) m.put(mm.group(1).toLowerCase(Locale.US), mm.group(3) != null ? mm.group(3) : mm.group(4));
        return m;
    }

    static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private String via() {
        return "Via: SIP/2.0/UDP " + localIp + ":" + localPort + ";branch=z9hG4bK" + hex(8) + ";rport\r\n";
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }

    static String param(String header, String name) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            String t = part.trim();
            if (t.startsWith(name + "=")) return t.substring(name.length() + 1);
        }
        return null;
    }

    static String between(String s, String a, String b) {
        int i = s.indexOf(a), j = s.indexOf(b, i + 1);
        return i >= 0 && j > i ? s.substring(i + 1, j) : null;
    }

    /** One parsed SIP message. */
    static final class Msg {
        String method;          // requests
        int code;               // responses
        String reason = "";
        final Map<String, java.util.List<String>> headers = new HashMap<String, java.util.List<String>>();
        String body = "";

        boolean isRequest() { return method != null; }

        String header(String name) {
            java.util.List<String> v = headers.get(name.toLowerCase(Locale.US));
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        java.util.List<String> all(String name) {
            java.util.List<String> v = headers.get(name.toLowerCase(Locale.US));
            return v != null ? v : new java.util.ArrayList<String>();
        }

        static Msg parse(String raw) {
            int split = raw.indexOf("\r\n\r\n");
            String head = split >= 0 ? raw.substring(0, split) : raw;
            Msg m = new Msg();
            m.body = split >= 0 ? raw.substring(split + 4) : "";
            String[] lines = head.split("\r\n");
            if (lines.length == 0) return null;
            String first = lines[0];
            if (first.startsWith("SIP/2.0 ")) {
                String[] f = first.split(" ", 3);
                try { m.code = Integer.parseInt(f[1]); } catch (Exception e) { return null; }
                m.reason = f.length > 2 ? f[2] : "";
            } else {
                m.method = first.split(" ")[0];
            }
            Map<String, String> compact = new HashMap<String, String>();
            compact.put("v", "via"); compact.put("f", "from"); compact.put("t", "to");
            compact.put("i", "call-id"); compact.put("m", "contact"); compact.put("l", "content-length");
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c <= 0) continue;
                String k = lines[i].substring(0, c).trim().toLowerCase(Locale.US);
                if (compact.containsKey(k)) k = compact.get(k);
                String v = lines[i].substring(c + 1).trim();
                java.util.List<String> list = m.headers.get(k);
                if (list == null) { list = new java.util.ArrayList<String>(); m.headers.put(k, list); }
                list.add(v);
            }
            return m;
        }
    }
}
