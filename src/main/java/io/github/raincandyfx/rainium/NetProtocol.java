package io.github.raincandyfx.rainium;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a Rainium server and a Rainium client say to each other, on one channel ({@link #CHANNEL}).
 * Every message is a plain byte array: one type byte, then its fields. It is the same on a Fabric
 * server and a Paper server (where it travels as a plugin message), so this class uses only Java.
 *
 * A worn outfit or cosmetic is a zip ("blob") named by the SHA-256 of its bytes. Blobs travel in
 * parts of at most {@link #PART} bytes each way, well under the game's 32 KiB limit for messages
 * sent by the client.
 */
public final class NetProtocol {
    private NetProtocol() {}

    public static final String CHANNEL = "rainium:net";
    public static final int VERSION = 2;
    /** Bytes of blob in one part. */
    public static final int PART = 28 * 1024;
    /** A blob is never bigger than this, whatever a server allows. */
    public static final int HARD_MAX_BLOB = 32 << 20;

    // server -> client
    public static final byte S_WELCOME = 1, S_WEARING = 2, S_PART = 3, S_NEED = 4, S_NOTICE = 5, S_ADMIN = 6;
    // client -> server
    public static final byte C_HELLO = 1, C_OUTFIT = 2, C_PART = 3, C_FETCH = 4, C_ADMIN_OPEN = 5, C_ADMIN_RUN = 6;

    /** Where a worn blob comes from: the player's own outfit, or a cosmetic the server installed. */
    public static final byte KIND_OWN = 0, KIND_SERVER = 1;

    public static final byte MODE_OWN = 0, MODE_SERVER_ONLY = 1;

    // ------------------------------------------------------------------ the messages

    /** ownAllowed: whether your own (client-side) cosmetics may show at all on this server; admin: whether you may manage it. */
    public record Welcome(int version, byte mode, boolean canUpload, int maxOutfitBytes, String server, boolean ownAllowed, boolean admin) {}
    public record Worn(byte kind, String hash, int size, String name) {}
    public record Wearing(UUID player, List<Worn> worn) {}
    public record Part(String hash, int index, int count, int size, byte[] data) {}
    public record Notice(String text) {}
    public record Hello(int version, String modVersion) {}
    public record Outfit(String hash, int size) {}   // hash "" = wearing nothing of my own

    public static byte[] welcome(Welcome w) {
        return frame(S_WELCOME, o -> {
            o.writeInt(w.version()); o.writeByte(w.mode()); o.writeBoolean(w.canUpload()); o.writeInt(w.maxOutfitBytes()); o.writeUTF(clip(w.server(), 100));
            o.writeBoolean(w.ownAllowed()); o.writeBoolean(w.admin());
        });
    }

    public static byte[] wearing(Wearing w) {
        return frame(S_WEARING, o -> {
            uuid(o, w.player());
            o.writeShort(w.worn().size());
            for (Worn x : w.worn()) { o.writeByte(x.kind()); o.writeUTF(x.hash()); o.writeInt(x.size()); o.writeUTF(clip(x.name(), 64)); }
        });
    }

    public static byte[] part(byte type, Part p) {
        return frame(type, o -> {
            o.writeUTF(p.hash()); o.writeInt(p.index()); o.writeInt(p.count()); o.writeInt(p.size());
            o.writeInt(p.data().length); o.write(p.data());
        });
    }

    public static byte[] need(String hash) { return frame(S_NEED, o -> o.writeUTF(hash)); }
    public static byte[] notice(String text) { return frame(S_NOTICE, o -> o.writeUTF(clip(text, 400))); }
    public static byte[] hello(Hello h) { return frame(C_HELLO, o -> { o.writeInt(h.version()); o.writeUTF(clip(h.modVersion(), 40)); }); }
    public static byte[] outfit(Outfit f) { return frame(C_OUTFIT, o -> { o.writeUTF(f.hash()); o.writeInt(f.size()); }); }
    public static byte[] fetch(String hash) { return frame(C_FETCH, o -> o.writeUTF(hash)); }
    public static byte[] adminOpen() { return frame(C_ADMIN_OPEN, o -> { }); }

    /** An admin panel action: the words of a /rainiumserver command. */
    public static byte[] adminRun(List<String> args) {
        return frame(C_ADMIN_RUN, o -> { o.writeShort(Math.min(args.size(), 16)); for (int i = 0; i < Math.min(args.size(), 16); i++) o.writeUTF(clip(args.get(i), 200)); });
    }

    /** The admin panel's contents, as JSON text (it can be longer than writeUTF allows). */
    public static byte[] admin(String json) {
        byte[] b = utf8(json);
        return frame(S_ADMIN, o -> { o.writeInt(b.length); o.write(b); });
    }

    public static List<String> readAdminRun(byte[] m) throws IOException {
        DataInputStream in = body(m);
        int n = Math.min(16, in.readUnsignedShort());
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(in.readUTF());
        return out;
    }

    public static String readAdmin(byte[] m) throws IOException {
        DataInputStream in = body(m);
        int len = in.readInt();
        if (len < 0 || len > (4 << 20)) throw new IOException("admin state too big");
        byte[] b = in.readNBytes(len);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** The type byte of a message, or -1 for an empty one. */
    public static byte type(byte[] msg) { return msg == null || msg.length == 0 ? -1 : msg[0]; }

    public static Welcome readWelcome(byte[] m) throws IOException {
        DataInputStream in = body(m);
        int version = in.readInt();
        if (version != VERSION) return new Welcome(version, MODE_OWN, false, 0, "", true, false);   // a different protocol: only the version is read
        return new Welcome(version, in.readByte(), in.readBoolean(), in.readInt(), in.readUTF(), in.readBoolean(), in.readBoolean());
    }

    public static Wearing readWearing(byte[] m) throws IOException {
        DataInputStream in = body(m);
        UUID u = uuid(in);
        int n = in.readUnsignedShort();
        if (n > 64) throw new IOException("too many worn items");
        List<Worn> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte kind = in.readByte();
            String hash = in.readUTF();
            int size = in.readInt();
            String name = in.readUTF();
            if (isHash(hash)) out.add(new Worn(kind, hash, size, name));
        }
        return new Wearing(u, out);
    }

    public static Part readPart(byte[] m) throws IOException {
        DataInputStream in = body(m);
        String hash = in.readUTF();
        int index = in.readInt(), count = in.readInt(), size = in.readInt(), len = in.readInt();
        if (len < 0 || len > PART) throw new IOException("part too big");
        byte[] data = in.readNBytes(len);
        if (data.length != len) throw new IOException("part cut short");
        return new Part(hash, index, count, size, data);
    }

    public static String readHash(byte[] m) throws IOException { return body(m).readUTF(); }
    public static Notice readNotice(byte[] m) throws IOException { return new Notice(body(m).readUTF()); }
    public static Hello readHello(byte[] m) throws IOException { DataInputStream in = body(m); return new Hello(in.readInt(), in.readUTF()); }
    public static Outfit readOutfit(byte[] m) throws IOException { DataInputStream in = body(m); return new Outfit(in.readUTF(), in.readInt()); }

    // ------------------------------------------------------------------ blobs

    /** The name of a blob: lower-case hex SHA-256 of its bytes. */
    public static String hash(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder b = new StringBuilder(64);
            for (byte x : d) b.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isHash(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }

    /** A blob cut into messages of the given type (S_PART or C_PART). */
    public static List<byte[]> split(byte type, String hash, byte[] data) {
        int count = Math.max(1, (data.length + PART - 1) / PART);
        List<byte[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int from = i * PART, to = Math.min(data.length, from + PART);
            byte[] piece = java.util.Arrays.copyOfRange(data, from, to);
            out.add(part(type, new Part(hash, i, count, data.length, piece)));
        }
        return out;
    }

    /**
     * Puts parts back together. Each blob being received has a size limit; parts that don't fit what
     * was announced are dropped with the blob, and a finished blob is kept only if its hash matches.
     */
    public static final class Assembler {
        private final int maxBlob, maxPending;
        private final Map<String, Pending> pending = new HashMap<>();

        public Assembler(int maxBlob, int maxPending) { this.maxBlob = maxBlob; this.maxPending = maxPending; }

        private static final class Pending {
            final byte[] data; final boolean[] got; int left; long touched;
            Pending(int size, int count) { data = new byte[size]; got = new boolean[count]; left = count; }
        }

        /** Adds a part. Returns the whole blob when this part completes it (and the hash checks out), otherwise null. */
        public byte[] add(Part p, long nowMs) {
            if (!isHash(p.hash())) return null;
            int size = p.size(), count = p.count();
            int expect = Math.max(1, (size + PART - 1) / PART);
            if (size < 0 || size > Math.min(maxBlob, HARD_MAX_BLOB) || count != expect || p.index() < 0 || p.index() >= count) { pending.remove(p.hash()); return null; }
            Pending b = pending.get(p.hash());
            if (b == null) {
                if (pending.size() >= maxPending) expireOldest();
                b = new Pending(size, count);
                pending.put(p.hash(), b);
            } else if (b.data.length != size || b.got.length != count) { pending.remove(p.hash()); return null; }
            int from = p.index() * PART, len = Math.min(PART, size - from);
            if (p.data().length != len) { pending.remove(p.hash()); return null; }
            b.touched = nowMs;
            if (!b.got[p.index()]) {
                System.arraycopy(p.data(), 0, b.data, from, len);
                b.got[p.index()] = true;
                b.left--;
            }
            if (b.left > 0) return null;
            pending.remove(p.hash());
            return hash(b.data).equals(p.hash()) ? b.data : null;
        }

        /** Drops blobs that have had no part for a while. */
        public void expire(long nowMs, long afterMs) { pending.values().removeIf(b -> nowMs - b.touched > afterMs); }

        public boolean receiving(String hash) { return pending.containsKey(hash); }

        public void clear() { pending.clear(); }

        private void expireOldest() {
            String oldest = null; long t = Long.MAX_VALUE;
            for (Map.Entry<String, Pending> e : pending.entrySet()) if (e.getValue().touched < t) { t = e.getValue().touched; oldest = e.getKey(); }
            if (oldest != null) pending.remove(oldest);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private interface Body { void write(DataOutputStream o) throws IOException; }

    private static byte[] frame(byte type, Body body) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeByte(type);
            body.write(o);
            o.flush();
            return bo.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DataInputStream body(byte[] m) {
        return new DataInputStream(new ByteArrayInputStream(m, 1, Math.max(0, m.length - 1)));
    }

    private static void uuid(DataOutputStream o, UUID u) throws IOException { o.writeLong(u.getMostSignificantBits()); o.writeLong(u.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
