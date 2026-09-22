package spritepack;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import doja.Resources;

/** 只要至少有一個 DoJa 影像控制代碼持有該資源，就讓每個原生精靈影像保持駐留。 */
final class SpriteRepository {
    private static final String INDEX_RESOURCE = "/assets/sprites/index.bin";
    private static final String NATIVE_PREFIX = "/assets/sprites/native/";
    private static boolean loaded;
    private static boolean unavailable;
    private static int[] slotPosition;
    private static short[] slotSprite;
    private static short[] width;
    private static short[] height;
    private static int[] encodedLength;
    private static int[] encodedHash;
    private static String[] nativePath;
    private static javax.microedition.lcdui.Image[] nativeSheet;
    private static short[] handleRefs;
    private static boolean[] loadFailed;

    private SpriteRepository() {}

    static int findByScratchpadUri(String uri) {
        int pos = parseScratchpadPosition(uri);
        if (pos < 0) return -1;
        try { ensureLoaded(); }
        catch (IOException failure) { return -1; }
        int low = 0;
        int high = slotPosition.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int value = slotPosition[middle];
            if (pos < value) high = middle - 1;
            else if (pos > value) low = middle + 1;
            else {
                int id = slotSprite[middle];
                return id < 0 ? -1 : id;
            }
        }
        return -1;
    }

    static int findByEncoded(byte[] encoded) {
        if (encoded == null || encoded.length == 0) return -1;
        try { ensureLoaded(); }
        catch (IOException failure) { return -1; }
        int hash = fnv1a(encoded, 0, encoded.length);
        for (int i = 0; i < encodedLength.length; i++) {
            if (encodedLength[i] == encoded.length && encodedHash[i] == hash) return i;
        }
        return -1;
    }

    static int getWidth(int spriteId) {
        ensureLoadedUnchecked();
        return width[spriteId] & 0xffff;
    }

    static int getHeight(int spriteId) {
        ensureLoadedUnchecked();
        return height[spriteId] & 0xffff;
    }

    static javax.microedition.lcdui.Image getNativeSheet(int spriteId) {
        if (!loaded) {
            try { ensureLoaded(); }
            catch (IOException failure) { return null; }
        }
        if (spriteId < 0 || spriteId >= width.length) return null;
        ensureNativeStructures();

        javax.microedition.lcdui.Image image = nativeSheet[spriteId];
        if (image != null) return image;
        if (loadFailed[spriteId]) return null;

        try {
            image = javax.microedition.lcdui.Image.createImage(nativePath[spriteId]);
        } catch (OutOfMemoryError first) {
            System.gc();
            try { image = javax.microedition.lcdui.Image.createImage(nativePath[spriteId]); }
            catch (Throwable second) { image = null; }
        } catch (Throwable failure) {
            image = null;
        }
        if (image == null) {
            loadFailed[spriteId] = true;
            return null;
        }
        nativeSheet[spriteId] = image;
        return image;
    }

    static void retain(int spriteId) {
        ensureLoadedUnchecked();
        ensureNativeStructures();
        if (spriteId < 0 || spriteId >= handleRefs.length) return;
        int refs = handleRefs[spriteId] & 0xffff;
        if (refs < 0xffff) handleRefs[spriteId] = (short)(refs + 1);
        if (refs == 0) loadFailed[spriteId] = false;
    }

    static void release(int spriteId) {
        if (spriteId < 0 || !loaded || handleRefs == null || spriteId >= handleRefs.length) return;
        int refs = handleRefs[spriteId] & 0xffff;
        if (refs > 0) {
            refs--;
            handleRefs[spriteId] = (short)refs;
        }
        if (refs == 0) {
            nativeSheet[spriteId] = null;
            loadFailed[spriteId] = false;
        }
    }

    private static synchronized void ensureLoaded() throws IOException {
        if (loaded) return;
        if (unavailable) throw new IOException("sprite index unavailable");
        InputStream raw = Resources.open(INDEX_RESOURCE);
        if (raw == null) {
            unavailable = true;
            throw new IOException("missing " + INDEX_RESOURCE);
        }
        DataInputStream in = new DataInputStream(raw);
        try {
            if (in.readUnsignedByte() != 'S' || in.readUnsignedByte() != 'P'
                    || in.readUnsignedByte() != 'N' || in.readUnsignedByte() != '4') {
                throw new IOException("invalid sprite index magic");
            }
            int slotCount = in.readInt();
            int spriteCount = in.readInt();
            if (slotCount <= 0 || spriteCount <= 0) throw new IOException("invalid sprite index size");

            slotPosition = new int[slotCount];
            slotSprite = new short[slotCount];
            for (int i = 0; i < slotCount; i++) {
                slotPosition[i] = in.readInt();
                slotSprite[i] = in.readShort();
            }

            width = new short[spriteCount];
            height = new short[spriteCount];
            encodedLength = new int[spriteCount];
            encodedHash = new int[spriteCount];
            nativePath = new String[spriteCount];
            for (int i = 0; i < spriteCount; i++) {
                width[i] = in.readShort();
                height[i] = in.readShort();
                encodedLength[i] = in.readInt();
                encodedHash[i] = in.readInt();
                nativePath[i] = NATIVE_PREFIX + i + ".png";
            }
            loaded = true;
            ensureNativeStructures();
        } catch (IOException failure) {
            unavailable = true;
            clearAll();
            throw failure;
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static void ensureLoadedUnchecked() {
        if (loaded) return;
        try { ensureLoaded(); }
        catch (IOException failure) { throw new IllegalStateException(failure.toString()); }
    }

    private static void ensureNativeStructures() {
        if (nativeSheet != null || width == null) return;
        nativeSheet = new javax.microedition.lcdui.Image[width.length];
        handleRefs = new short[width.length];
        loadFailed = new boolean[width.length];
    }

    private static void clearAll() {
        slotPosition = null;
        slotSprite = null;
        width = null;
        height = null;
        encodedLength = null;
        encodedHash = null;
        nativePath = null;
        nativeSheet = null;
        handleRefs = null;
        loadFailed = null;
    }

    private static int parseScratchpadPosition(String uri) {
        if (uri == null || !uri.startsWith("scratchpad:///")) return -1;
        int marker = uri.indexOf(";pos=");
        if (marker < 0) return 0;
        int p = marker + 5;
        int end = p;
        while (end < uri.length()) {
            char c = uri.charAt(end);
            if (c < '0' || c > '9') break;
            end++;
        }
        if (end == p) return -1;
        try { return Integer.parseInt(uri.substring(p, end)); }
        catch (Exception ignored) { return -1; }
    }

    private static int fnv1a(byte[] bytes, int offset, int length) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < length; i++) {
            hash ^= bytes[offset + i] & 0xff;
            hash *= 0x01000193;
        }
        return hash;
    }
}
