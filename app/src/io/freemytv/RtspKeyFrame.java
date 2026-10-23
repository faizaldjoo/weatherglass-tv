package io.freemytv;

import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an RTSP camera: one key frame for its still picture (read), or the live video for the popup (stream). Android's own player can't log in to some cameras
 * (it gets 401 twice from the EyeSec), so this speaks just enough RTSP itself: DESCRIBE with Digest or Basic login,
 * SETUP of the video track over the same TCP connection, PLAY, then RTP until a whole key frame (H.264 or H.265) has
 * arrived, and TEARDOWN. The result is the parameter sets plus that frame, in Annex B form, ready for MediaCodec.
 */
final class RtspKeyFrame {
    static final class Frame {
        String mime;    // "video/avc" or "video/hevc"
        byte[] data;    // parameter sets + the key frame, each NAL unit after a 00 00 00 01 start code
    }

    private static final byte[] START = {0, 0, 0, 1};

    private final String url, user, password;
    private final String host;
    private final int port;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int cseq;
    private String session, authHeader;   // the camera's WWW-Authenticate, once it asked
    private boolean hevc;
    private String base;
    private List<byte[]> sdpParams;
    private ByteArrayOutputStream fu;   // a fragmented NAL unit being put back together

    RtspKeyFrame(String rtspUrl) {
        Uri u = Uri.parse(rtspUrl);
        host = u.getHost();
        port = u.getPort() > 0 ? u.getPort() : 554;
        String info = u.getUserInfo() == null ? "" : u.getUserInfo();
        int colon = info.indexOf(':');
        user = Uri.decode(colon < 0 ? info : info.substring(0, colon));
        password = Uri.decode(colon < 0 ? "" : info.substring(colon + 1));
        // The address without the login, as RTSP requests carry it
        url = "rtsp://" + host + ":" + port + (u.getEncodedPath() == null ? "/" : u.getEncodedPath())
                + (u.getEncodedQuery() == null ? "" : "?" + u.getEncodedQuery());
    }

    /** Closes the connection (from another thread: it ends a read that's waiting). */
    void cancel() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    /** Connects, logs in and starts the video: DESCRIBE, SETUP over this connection, PLAY. */
    private void open() throws Exception {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 4000);
        socket.setSoTimeout(5000);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        // DESCRIBE: the tracks and their codecs
        Map<String, String> h = new HashMap<>();
        String sdp = request("DESCRIBE", url, "Accept: application/sdp\r\n", h);
        base = h.containsKey("content-base") ? h.get("content-base") : url;
        String video = videoSection(sdp);
        if (video == null) throw new IOException("no video track");
        hevc = video.matches("(?s).*rtpmap:\\d+\\s+(H265|HEVC)/.*");
        if (!hevc && !video.matches("(?s).*rtpmap:\\d+\\s+H264/.*")) throw new IOException("video isn't H.264 or H.265");
        String control = find(video, "a=control:\\s*(\\S+)");
        String track = control == null || control.equals("*") ? base
                : control.startsWith("rtsp://") ? control : (base.endsWith("/") ? base : base + "/") + control;
        sdpParams = sdpParams(video);

        // SETUP over this connection (RTP arrives as "$" frames on channel 0), then PLAY
        h.clear();
        request("SETUP", track, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n", h);
        if (h.get("session") != null) session = h.get("session").split(";")[0].trim();
        h.clear();
        request("PLAY", base, "Range: npt=0.000-\r\n", h);
    }

    private void teardown() {
        try {
            send("TEARDOWN", base, "");
        } catch (Exception ignored) {
        }
    }

    /** One key frame, for a still picture. */
    Frame read(int timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            open();
            byte[] frame = keyFrame(sdpParams, deadline);
            Frame f = new Frame();
            f.mime = mime();
            f.data = frame;
            teardown();
            return f;
        } finally {
            cancel();
        }
    }

    String mime() {
        return hevc ? "video/hevc" : "video/avc";
    }

    /** Gets each frame of the live video, in Annex B form (key frames with their parameter sets first). */
    interface Sink {
        /** The codec is known (mime()) before the first frame. False stops the stream. */
        boolean frame(byte[] data, long timestamp90k, boolean key) throws Exception;
    }

    /** The live video, frame by frame, from the first key frame on, until the sink says stop or cancel() is called. */
    void stream(Sink sink) throws Exception {
        try {
            open();
            Map<Integer, byte[]> params = new HashMap<>();
            for (byte[] p : sdpParams) if (p.length > 0) params.put(type(p), p);
            List<byte[]> au = new ArrayList<>();
            long auTs = -1, keepAlive = System.currentTimeMillis() + 25_000;
            boolean auKey = false, started = false;
            while (true) {
                if (System.currentTimeMillis() > keepAlive) {   // so the camera doesn't end the session
                    send("OPTIONS", base, "");
                    keepAlive = System.currentTimeMillis() + 25_000;
                }
                Packet pk = packet();
                if (pk == null) continue;
                if (!au.isEmpty() && pk.ts != auTs) {   // the last frame ended without a marker
                    if (auKey && hasParams(params)) started = true;
                    if (started && !sink.frame(annexB(auKey ? params : null, au), auTs, auKey)) break;
                    au.clear();
                    auKey = false;
                }
                for (byte[] nal : pk.nals) {
                    int nt = type(nal);
                    if (isParam(nt)) {
                        params.put(nt, nal);
                    } else {
                        au.add(nal);
                        if (isKey(nt)) auKey = true;
                    }
                }
                auTs = pk.ts;
                if (pk.marker && !au.isEmpty()) {
                    if (auKey && hasParams(params)) started = true;
                    if (started && !sink.frame(annexB(auKey ? params : null, au), auTs, auKey)) break;
                    au.clear();
                    auKey = false;
                }
            }
            teardown();
        } finally {
            cancel();
        }
    }

    private boolean hasParams(Map<Integer, byte[]> params) {
        for (int t : hevc ? new int[] {32, 33, 34} : new int[] {7, 8}) if (!params.containsKey(t)) return false;
        return true;
    }

    // MARK: - RTSP requests

    private String request(String method, String target, String extra, Map<String, String> headers) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            send(method, target, extra);
            int status = readResponse(headers);
            String body = headers.remove("\u0000body");
            if (status == 401 && attempt == 0 && headers.containsKey("www-authenticate")) {
                authHeader = headers.get("www-authenticate");
                headers.clear();
                continue;
            }
            if (status != 200) throw new IOException(method + " answered " + status);
            return body == null ? "" : body;
        }
        throw new IOException(method + ": login refused");
    }

    private void send(String method, String target, String extra) throws Exception {
        StringBuilder s = new StringBuilder(method).append(' ').append(target).append(" RTSP/1.0\r\n")
                .append("CSeq: ").append(++cseq).append("\r\n")
                .append("User-Agent: FreeMyTV\r\n");
        if (session != null) s.append("Session: ").append(session).append("\r\n");
        if (authHeader != null) s.append("Authorization: ").append(authorization(method, target)).append("\r\n");
        s.append(extra).append("\r\n");
        out.write(s.toString().getBytes("UTF-8"));
        out.flush();
    }

    /** Status line and headers (names in lower case; several WWW-Authenticate: Digest wins), then the body. */
    private int readResponse(Map<String, String> headers) throws Exception {
        String line = readLine();
        while (line.isEmpty() || line.charAt(0) == '$') line = readLine();   // skip anything before the reply
        Matcher m = Pattern.compile("RTSP/1\\.0\\s+(\\d+)").matcher(line);
        if (!m.find()) throw new IOException("not an RTSP reply: " + line);
        int status = Integer.parseInt(m.group(1));
        int length = 0;
        for (String l; !(l = readLine()).isEmpty(); ) {
            int colon = l.indexOf(':');
            if (colon < 0) continue;
            String name = l.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = l.substring(colon + 1).trim();
            if (name.equals("content-length")) length = Integer.parseInt(value);
            if (name.equals("www-authenticate") && headers.containsKey(name) && !value.toLowerCase(Locale.ROOT).startsWith("digest")) continue;
            headers.put(name, value);
        }
        if (length > 0) {
            byte[] body = new byte[length];
            in.readFully(body);
            headers.put("\u0000body", new String(body, "UTF-8"));
        }
        return status;
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0) throw new IOException("connection closed");
            if (c != '\r') b.write(c);
        }
        return b.toString("UTF-8");
    }

    private String authorization(String method, String target) throws Exception {
        if (!authHeader.toLowerCase(Locale.ROOT).startsWith("digest")) {
            return "Basic " + Base64.encodeToString((user + ":" + password).getBytes("UTF-8"), Base64.NO_WRAP);
        }
        String realm = param("realm"), nonce = param("nonce"), qop = param("qop"), opaque = param("opaque");
        String ha1 = md5(user + ":" + realm + ":" + password), ha2 = md5(method + ":" + target);
        StringBuilder s = new StringBuilder("Digest username=\"").append(user).append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(target).append('"');
        if (qop != null && qop.contains("auth")) {
            String nc = String.format(Locale.ROOT, "%08x", cseq), cnonce = Long.toHexString(System.nanoTime());
            s.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2))
                    .append("\", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
        } else {
            s.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append('"');
        }
        if (opaque != null) s.append(", opaque=\"").append(opaque).append('"');
        return s.toString();
    }

    private String param(String name) {
        Matcher m = Pattern.compile(name + "=\"?([^\",]*)\"?").matcher(authHeader);
        return m.find() ? m.group(1) : null;
    }

    private static String md5(String s) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte x : MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"))) b.append(String.format("%02x", x));
        return b.toString();
    }

    // MARK: - SDP

    private static String videoSection(String sdp) {
        int at = sdp.indexOf("m=video");
        if (at < 0) return null;
        int end = sdp.indexOf("\nm=", at + 1);
        return end < 0 ? sdp.substring(at) : sdp.substring(at, end);
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** sprop-parameter-sets (H.264) or sprop-vps/sps/pps (H.265), if the SDP has them. */
    private List<byte[]> sdpParams(String video) {
        List<byte[]> list = new ArrayList<>();
        String[] keys = hevc ? new String[] {"sprop-vps", "sprop-sps", "sprop-pps"} : new String[] {"sprop-parameter-sets"};
        for (String key : keys) {
            String v = find(video, key + "=([^;\\s]+)");
            if (v == null) continue;
            for (String part : v.split(",")) {
                try {
                    if (!part.isEmpty()) list.add(Base64.decode(part, Base64.DEFAULT));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return list;
    }

    // MARK: - RTP

    private static final class Packet {
        long ts;
        boolean marker;
        List<byte[]> nals = new ArrayList<>();
    }

    /** The next RTP packet of the video as NAL units (null: something else, e.g. an RTSP reply or RTCP). */
    private Packet packet() throws Exception {
        int b = in.read();
        if (b < 0) throw new IOException("connection closed");
        if (b != '$') {   // an RTSP message in between (e.g. a keep-alive reply): skip its headers and body
            if (b == 'R') {
                readLine();
                int length = 0;
                for (String l; !(l = readLine()).isEmpty(); ) {
                    if (l.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(l.substring(15).trim());
                }
                in.skipBytes(length);
            }
            return null;
        }
        int channel = in.readUnsignedByte(), length = in.readUnsignedShort();
        byte[] packet = new byte[length];
        in.readFully(packet);
        if (channel != 0 || length < 12) return null;
        // RTP header
        Packet pk = new Packet();
        int cc = packet[0] & 0x0F;
        boolean ext = (packet[0] & 0x10) != 0, padding = (packet[0] & 0x20) != 0;
        pk.marker = (packet[1] & 0x80) != 0;
        pk.ts = ((packet[4] & 0xFFL) << 24) | ((packet[5] & 0xFF) << 16) | ((packet[6] & 0xFF) << 8) | (packet[7] & 0xFF);
        int off = 12 + cc * 4, end = length - (padding ? packet[length - 1] & 0xFF : 0);
        if (ext && off + 4 <= end) off += 4 + (((packet[off + 2] & 0xFF) << 8) | (packet[off + 3] & 0xFF)) * 4;
        if (off >= end) return null;

        // Payload → NAL units
        int t = hevc ? (packet[off] >> 1) & 0x3F : packet[off] & 0x1F;
        if (!hevc && t == 24 || hevc && t == 48) {   // aggregation: several small NAL units
            int p = off + (hevc ? 2 : 1);
            while (p + 2 <= end) {
                int n = ((packet[p] & 0xFF) << 8) | (packet[p + 1] & 0xFF);
                p += 2;
                if (n <= 0 || p + n > end) break;
                pk.nals.add(slice(packet, p, n));
                p += n;
            }
        } else if (!hevc && t == 28 || hevc && t == 49) {   // fragment of a large NAL unit
            int head = hevc ? 3 : 2, fuHeader = packet[off + head - 1] & 0xFF;
            boolean start = (fuHeader & 0x80) != 0, stop = (fuHeader & 0x40) != 0;
            if (start) {
                fu = new ByteArrayOutputStream();
                if (hevc) {
                    int nalType = fuHeader & 0x3F;
                    fu.write((packet[off] & 0x81) | (nalType << 1));
                    fu.write(packet[off + 1]);
                } else {
                    fu.write((packet[off] & 0xE0) | (fuHeader & 0x1F));
                }
            }
            if (fu != null) fu.write(packet, off + head, end - off - head);
            if (stop && fu != null) {
                pk.nals.add(fu.toByteArray());
                fu = null;
            }
        } else {
            pk.nals.add(slice(packet, off, end - off));
        }
        return pk;
    }

    /** NAL units until a whole key frame. */
    private byte[] keyFrame(List<byte[]> sdpParams, long deadline) throws Exception {
        Map<Integer, byte[]> params = new HashMap<>();   // by NAL type, the latest of each
        for (byte[] p : sdpParams) if (p.length > 0) params.put(type(p), p);
        List<byte[]> frame = new ArrayList<>();            // NAL units of the key frame being collected
        boolean collecting = false;
        long frameTs = -1;
        while (System.currentTimeMillis() < deadline) {
            Packet pk = packet();
            if (pk == null) continue;
            if (collecting && pk.ts != frameTs) return annexB(params, frame);   // the key frame ended without a marker
            for (byte[] nal : pk.nals) {
                int nt = type(nal);
                if (isParam(nt)) {
                    params.put(nt, nal);
                } else if (isKey(nt)) {
                    if (!collecting) {
                        collecting = true;
                        frameTs = pk.ts;
                    }
                    frame.add(nal);
                } else if (collecting && pk.ts == frameTs) {
                    frame.add(nal);   // e.g. SEI inside the key frame
                }
            }
            if (collecting && pk.marker && pk.ts == frameTs) return annexB(params, frame);
        }
        throw new IOException("no key frame within the time limit");
    }

    private int type(byte[] nal) {
        return hevc ? (nal[0] >> 1) & 0x3F : nal[0] & 0x1F;
    }

    private boolean isParam(int t) {
        return hevc ? t >= 32 && t <= 34 : t == 7 || t == 8;
    }

    private boolean isKey(int t) {
        return hevc ? t >= 16 && t <= 21 : t == 5;
    }

    private byte[] annexB(Map<Integer, byte[]> params, List<byte[]> frame) throws IOException {
        int[] order = hevc ? new int[] {32, 33, 34} : new int[] {7, 8};
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (params != null) for (int t : order) {
            if (!params.containsKey(t)) throw new IOException("the stream gave no parameter sets");
            b.write(START);
            b.write(params.get(t));
        }
        for (byte[] nal : frame) {
            b.write(START);
            b.write(nal);
        }
        return b.toByteArray();
    }

    private static byte[] slice(byte[] a, int from, int n) {
        byte[] r = new byte[n];
        System.arraycopy(a, from, r, 0, n);
        return r;
    }
}
