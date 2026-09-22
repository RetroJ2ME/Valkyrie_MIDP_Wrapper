package spritepack;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

import doja.tools.image.GifImage;
import doja.tools.io.FileIO;

/**
 * Valkyrie 1 精靈表轉接器。
 * 處理 Scratchpad 表格與 SPN4 執行期索引契約。
 */
public final class SpritePackBuild {
    private SpritePackBuild() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: SpritePackBuild <logical-scratchpad.bin> <generated-dir> <slot-count>");
        }
        build(new File(args[0]), new File(args[1]), Integer.parseInt(args[2]));
    }

    private static void build(File input, File generatedDir, int slotCount) throws Exception {
        byte[] logical = FileIO.read(input);
        if (logical.length == 0) throw new IOException("scratchpad is empty");
        if (slotCount <= 0 || slotCount > logical.length / 4) {
            throw new IOException("invalid static image slot count");
        }
        int staticBase = findStaticBase(logical, slotCount);

        int[] offsets = new int[slotCount];
        for (int i = 0; i < slotCount; i++) offsets[i] = readInt(logical, staticBase + i * 4);

        Map<Integer,Integer> offsetToSprite = new HashMap<Integer,Integer>();
        List<Sprite> sprites = new ArrayList<Sprite>();
        File nativeDir = new File(new File(new File(generatedDir, "assets"), "sprites"), "native");
        FileIO.ensureDirectory(nativeDir);

        int maxWidth = 0;
        int maxHeight = 0;
        long pngBytes = 0;

        for (int i = 0; i < slotCount; i++) {
            Integer key = Integer.valueOf(offsets[i]);
            if (offsetToSprite.containsKey(key)) continue;
            int start = staticBase + offsets[i];
            if (!GifImage.startsAt(logical, start)) {
                offsetToSprite.put(key, Integer.valueOf(-1));
                continue;
            }

            BufferedImage image;
            int width;
            int height;
            int encodedLength;
            int encodedHash;
            try {
                GifImage.Result gif = GifImage.decode(logical, start);
                image = gif.image;
                width = gif.width;
                height = gif.height;
                encodedLength = gif.encodedLength;
                encodedHash = gif.encodedHash;
            } catch (IOException failure) {
                if (!failure.getMessage().startsWith("GIF uses too many opaque palette entries")) throw failure;
                encodedLength = gifLength(logical, start);
                image = ImageIO.read(new ByteArrayInputStream(logical, start, encodedLength));
                if (image == null) throw new IOException("cannot decode GIF at " + start);
                width = image.getWidth();
                height = image.getHeight();
                encodedHash = fnv1a(logical, start, encodedLength);
                System.out.println("SpritePackBuild: preserved full GIF palette at " + start);
            }
            Sprite sprite = new Sprite(width, height, encodedLength, encodedHash);
            int id = sprites.size();
            sprites.add(sprite);
            offsetToSprite.put(key, Integer.valueOf(id));

            File png = new File(nativeDir, Integer.toString(id) + ".png");
            if (!ImageIO.write(image, "png", png)) throw new IOException("no PNG writer available");
            pngBytes += png.length();
            if (sprite.width > maxWidth) maxWidth = sprite.width;
            if (sprite.height > maxHeight) maxHeight = sprite.height;
        }

        if (sprites.isEmpty()) throw new IOException("no GIF sprites found");

        File spritesDir = new File(new File(generatedDir, "assets"), "sprites");
        ByteArrayOutputStream indexBytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(indexBytes);
        out.writeBytes("SPN4");
        out.writeInt(slotCount);
        out.writeInt(sprites.size());
        for (int i = 0; i < slotCount; i++) {
            out.writeInt(staticBase + offsets[i]);
            Integer id = offsetToSprite.get(Integer.valueOf(offsets[i]));
            out.writeShort(id == null ? -1 : id.intValue());
        }
        for (int i = 0; i < sprites.size(); i++) {
            Sprite sprite = sprites.get(i);
            out.writeShort(sprite.width);
            out.writeShort(sprite.height);
            out.writeInt(sprite.encodedLength);
            out.writeInt(sprite.encodedHash);
        }
        out.close();
        FileIO.write(new File(spritesDir, "index.bin"), indexBytes.toByteArray());

        System.out.println("SpritePackBuild: " + sprites.size() + " indexed PNG sprites, "
                + pngBytes + " PNG bytes, max " + maxWidth + "x" + maxHeight);
    }
    private static int findStaticBase(byte[] logical, int slotCount) throws IOException {
        int tableBytes = slotCount * 4;
        for (int firstGif = tableBytes; firstGif + 4 <= logical.length; firstGif++) {
            if (!GifImage.startsAt(logical, firstGif)) continue;
            int base = firstGif - tableBytes;
            int min = Integer.MAX_VALUE;
            int gifTargets = 0;
            boolean valid = true;
            for (int i = 0; i < slotCount; i++) {
                int relative = readInt(logical, base + i * 4);
                if (relative < tableBytes || relative > logical.length - base) {
                    valid = false;
                    break;
                }
                if (relative < min) min = relative;
                int absolute = base + relative;
                if (absolute + 4 <= logical.length && GifImage.startsAt(logical, absolute)) gifTargets++;
            }
            if (valid && min == tableBytes && gifTargets != 0) return base;
        }
        throw new IOException("Valkyrie 1 sprite table not found");
    }

    private static int gifLength(byte[] data, int start) throws IOException {
        if (!GifImage.startsAt(data, start) || start + 13 > data.length) throw new IOException("invalid GIF header");
        int position = start + 13;
        int packed = data[start + 10] & 0xff;
        if ((packed & 0x80) != 0) position += 3 * (1 << ((packed & 7) + 1));
        while (position < data.length) {
            int marker = data[position++] & 0xff;
            if (marker == 0x3b) return position - start;
            if (marker == 0x21) {
                if (position >= data.length) break;
                position++;
                position = skipSubBlocks(data, position);
            } else if (marker == 0x2c) {
                if (position + 9 > data.length) break;
                int flags = data[position + 8] & 0xff;
                position += 9;
                if ((flags & 0x80) != 0) position += 3 * (1 << ((flags & 7) + 1));
                if (position >= data.length) break;
                position++;
                position = skipSubBlocks(data, position);
            } else {
                throw new IOException("unexpected GIF marker 0x" + Integer.toHexString(marker));
            }
        }
        throw new IOException("unterminated GIF at " + start);
    }

    private static int skipSubBlocks(byte[] data, int position) throws IOException {
        while (position < data.length) {
            int size = data[position++] & 0xff;
            if (size == 0) return position;
            if (position + size > data.length) throw new IOException("truncated GIF sub-block");
            position += size;
        }
        throw new IOException("unterminated GIF sub-block");
    }

    private static int fnv1a(byte[] bytes, int offset, int length) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < length; i++) {
            hash ^= bytes[offset + i] & 0xff;
            hash *= 0x01000193;
        }
        return hash;
    }

    private static int readInt(byte[] data, int position) {
        return ((data[position] & 0xff) << 24) | ((data[position + 1] & 0xff) << 16)
                | ((data[position + 2] & 0xff) << 8) | (data[position + 3] & 0xff);
    }

    private static final class Sprite {
        final int width;
        final int height;
        final int encodedLength;
        final int encodedHash;

        Sprite(int width, int height, int encodedLength, int encodedHash) {
            this.width = width;
            this.height = height;
            this.encodedLength = encodedLength;
            this.encodedHash = encodedHash;
        }
    }
}
