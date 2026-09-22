package doja;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Valkyrie 1 資源橋。
 * Scratchpad 基線由精簡啟動 seed 與索引動態重建。 
 */
public final class Resources {
    private static final Object ANCHOR = new Resources();
    private static final String BLOCK_PREFIX = "/assets/sp/b";
    private static final String BLOCK_SUFFIX = ".bin";
    private static final String META = "/assets/sp/meta.bin";
    private static final String SEED = "/assets/sp/seed.bin";
    private static final String ARCHIVES = "/assets/index.bin";
    private static final String SPRITES = "/assets/sprites/index.bin";

    private static boolean scratchpadIndexLoaded;
    private static int scratchpadSize;
    private static int blockSize;
    private static int blockCount;
    private static byte[] scratchpadSeed;
    private static int[] archivePosition;
    private static int[] archiveLength;
    private static int spriteTableBase = -1;
    private static int[] spritePosition;

    private Resources() {}

    public static InputStream open(String path) {
        InputStream direct = raw(path);
        if (direct != null) return direct;
        int block = parseScratchpadBlock(path);
        if (block < 0) return null;
        try {
            ensureScratchpadIndex();
            if (block >= blockCount) return null;
            int start = block * blockSize;
            int length = Math.min(blockSize, scratchpadSize - start);
            byte[] data = new byte[length];
            synthesizeSeed(data, start);
            synthesizeArchiveFrames(data, start);
            synthesizeSpriteTable(data, start);
            return new ByteArrayInputStream(data);
        } catch (IOException failure) {
            return null;
        }
    }

    private static InputStream raw(String path) {
        return ANCHOR.getClass().getResourceAsStream(path);
    }

    private static synchronized void ensureScratchpadIndex() throws IOException {
        if (scratchpadIndexLoaded) return;
        loadMeta();
        loadArchives();
        loadSeed();
        loadSprites();
        scratchpadIndexLoaded = true;
    }

    private static void loadMeta() throws IOException {
        DataInputStream in = input(META);
        try {
            if (in.readUnsignedByte() != 'S' || in.readUnsignedByte() != 'P'
                    || in.readUnsignedByte() != 'B' || in.readUnsignedByte() != 'M') {
                throw new IOException("invalid scratchpad metadata");
            }
            scratchpadSize = in.readInt();
            blockSize = in.readInt();
            blockCount = in.readInt();
            if (scratchpadSize < 0 || blockSize <= 0 || blockCount <= 0
                    || blockCount != (scratchpadSize + blockSize - 1) / blockSize) {
                throw new IOException("invalid scratchpad geometry");
            }
        } finally {
            in.close();
        }
    }

    private static void loadArchives() throws IOException {
        DataInputStream in = input(ARCHIVES);
        try {
            if (in.readUnsignedByte() != 'S' || in.readUnsignedByte() != 'P'
                    || in.readUnsignedByte() != 'A' || in.readUnsignedByte() != 'R') {
                throw new IOException("invalid archive index");
            }
            int count = in.readUnsignedShort();
            archivePosition = new int[count];
            archiveLength = new int[count];
            for (int i = 0; i < count; i++) {
                archivePosition[i] = in.readInt();
                archiveLength[i] = in.readInt();
                in.readUnsignedShort();
                if (archivePosition[i] < 4 || archiveLength[i] < 0
                        || archivePosition[i] > scratchpadSize
                        || archiveLength[i] > scratchpadSize - archivePosition[i]) {
                    throw new IOException("invalid archive range");
                }
            }
        } finally {
            in.close();
        }
    }

    private static void loadSeed() throws IOException {
        InputStream in = raw(SEED);
        if (in == null) throw new IOException("missing " + SEED);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) out.write(buffer, 0, read);
            }
            scratchpadSeed = out.toByteArray();
        } finally {
            in.close();
        }
        int firstFrame = scratchpadSize;
        for (int i = 0; i < archivePosition.length; i++) {
            int frame = archivePosition[i] - 4;
            if (frame < firstFrame) firstFrame = frame;
        }
        // seed 可以短於第一個封存資料框；中間缺失的部分就是乾淨的零基底。
        if (firstFrame < 0 || scratchpadSeed.length > firstFrame) {
            throw new IOException("invalid Valkyrie 1 boot seed length " + scratchpadSeed.length
                    + ", first archive frame " + firstFrame);
        }
    }

    private static void loadSprites() throws IOException {
        DataInputStream in = input(SPRITES);
        try {
            if (in.readUnsignedByte() != 'S' || in.readUnsignedByte() != 'P'
                    || in.readUnsignedByte() != 'N' || in.readUnsignedByte() != '4') {
                throw new IOException("invalid sprite index");
            }
            int slotCount = in.readInt();
            int spriteCount = in.readInt();
            if (slotCount <= 0 || spriteCount <= 0) throw new IOException("invalid sprite index size");
            spritePosition = new int[slotCount];
            int first = Integer.MAX_VALUE;
            for (int i = 0; i < slotCount; i++) {
                int position = in.readInt();
                in.readShort();
                spritePosition[i] = position;
                if (position < first) first = position;
            }
            spriteTableBase = first - slotCount * 4;
            if (spriteTableBase < 0 || spriteTableBase + slotCount * 4 > scratchpadSize) {
                throw new IOException("invalid sprite table geometry");
            }
            for (int i = 0; i < slotCount; i++) {
                if (spritePosition[i] < spriteTableBase + slotCount * 4
                        || spritePosition[i] > scratchpadSize) {
                    throw new IOException("invalid sprite slot position");
                }
            }
        } finally {
            in.close();
        }
    }

    private static DataInputStream input(String path) throws IOException {
        InputStream raw = raw(path);
        if (raw == null) throw new IOException("missing " + path);
        return new DataInputStream(raw);
    }

    private static void synthesizeSeed(byte[] block, int blockStart) {
        if (scratchpadSeed == null || blockStart >= scratchpadSeed.length) return;
        int count = Math.min(block.length, scratchpadSeed.length - blockStart);
        if (count > 0) System.arraycopy(scratchpadSeed, blockStart, block, 0, count);
    }

    private static void synthesizeArchiveFrames(byte[] block, int blockStart) {
        for (int i = 0; i < archivePosition.length; i++) {
            putInt(block, blockStart, archivePosition[i] - 4, archiveLength[i]);
        }
    }

    private static void synthesizeSpriteTable(byte[] block, int blockStart) {
        if (spritePosition == null) return;
        for (int i = 0; i < spritePosition.length; i++) {
            int relative = spritePosition[i] - spriteTableBase;
            putInt(block, blockStart, spriteTableBase + i * 4, relative);
        }
    }

    private static void putInt(byte[] block, int blockStart, int absolute, int value) {
        int p = absolute - blockStart;
        if (p <= -4 || p >= block.length) return;
        for (int i = 0; i < 4; i++) {
            int at = p + i;
            if (at >= 0 && at < block.length) block[at] = (byte)(value >>> (24 - i * 8));
        }
    }

    private static int parseScratchpadBlock(String path) {
        if (path == null || !path.startsWith(BLOCK_PREFIX) || !path.endsWith(BLOCK_SUFFIX)) return -1;
        int start = BLOCK_PREFIX.length();
        int end = path.length() - BLOCK_SUFFIX.length();
        if (end <= start) return -1;
        int value = 0;
        for (int i = start; i < end; i++) {
            char c = path.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + (c - '0');
        }
        return value;
    }
}
