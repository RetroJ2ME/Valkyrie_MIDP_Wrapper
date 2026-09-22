package spritepack;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
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
 * Valkyrie 2 精靈表轉接器。
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
        int opaqueSprites = 0;

        for (int i = 0; i < slotCount; i++) {
            Integer key = Integer.valueOf(offsets[i]);
            if (offsetToSprite.containsKey(key)) continue;
            int start = staticBase + offsets[i];
            if (!GifImage.startsAt(logical, start)) {
                offsetToSprite.put(key, Integer.valueOf(-1));
                continue;
            }

            GifImage.Result gif = GifImage.decode(logical, start);
            Sprite sprite = new Sprite(gif.width, gif.height, gif.encodedLength, gif.encodedHash);
            int id = sprites.size();
            sprites.add(sprite);
            offsetToSprite.put(key, Integer.valueOf(id));

            File png = new File(nativeDir, Integer.toString(id) + ".png");
            BufferedImage pngImage = compactPalette(gif.image);
            if (!pngImage.getColorModel().hasAlpha()) opaqueSprites++;
            if (!ImageIO.write(pngImage, "png", png)) throw new IOException("no PNG writer available");
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
                + pngBytes + " PNG bytes, " + opaqueSprites + " opaque, max "
                + maxWidth + "x" + maxHeight);
    }

    /**
     * 重新建立索引圖，只保留實際被像素參照的調色盤項目。RGBA 輸出不變；
     * 不透明圖表也會移除未使用的透明度中繼資料，以最大化縮減圖形大小（雖然沒什麼明顯效果）。
     */
    private static BufferedImage compactPalette(BufferedImage image) {
        if (!(image.getColorModel() instanceof IndexColorModel)) return image;
        IndexColorModel colors = (IndexColorModel)image.getColorModel();
        WritableRaster source = image.getRaster();
        int width = image.getWidth();
        int height = image.getHeight();
        int mapSize = colors.getMapSize();
        boolean[] used = new boolean[mapSize];
        int usedCount = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = source.getSample(x, y, 0);
                if (!used[index]) { used[index] = true; usedCount++; }
            }
        }
        if (usedCount <= 0) return image;

        int[] remap = new int[mapSize];
        byte[] red = new byte[usedCount];
        byte[] green = new byte[usedCount];
        byte[] blue = new byte[usedCount];
        byte[] alpha = new byte[usedCount];
        boolean opaque = true;
        int next = 0;
        for (int old = 0; old < mapSize; old++) {
            if (!used[old]) continue;
            remap[old] = next;
            red[next] = (byte)colors.getRed(old);
            green[next] = (byte)colors.getGreen(old);
            blue[next] = (byte)colors.getBlue(old);
            int a = colors.getAlpha(old);
            alpha[next] = (byte)a;
            if (a != 255) opaque = false;
            next++;
        }

        int bits = usedCount <= 2 ? 1 : usedCount <= 4 ? 2 : usedCount <= 16 ? 4 : 8;
        IndexColorModel compact = opaque
                ? new IndexColorModel(bits, usedCount, red, green, blue)
                : new IndexColorModel(bits, usedCount, red, green, blue, alpha);
        WritableRaster raster = compact.createCompatibleWritableRaster(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.setSample(x, y, 0, remap[source.getSample(x, y, 0)]);
            }
        }
        return new BufferedImage(compact, raster, false, null);
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
        throw new IOException("Valkyrie 2 sprite table not found");
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
