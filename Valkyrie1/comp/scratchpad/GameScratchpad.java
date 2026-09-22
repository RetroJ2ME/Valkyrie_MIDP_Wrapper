import java.io.IOException;
import java.util.List;

import doja.tools.scratchpad.Scratchpad;

/** Valkyrie 1 的 Scratchpad 私有佈局。 */
public final class GameScratchpad implements Scratchpad.Schema {
    private static final int INITIALIZATION_MARKER = 80;
    private static final int SAVE_SLOT_START = 184;
    private static final int SPRITE_SLOT_COUNT = 683;
    private static final int SPRITE_TABLE_BYTES = SPRITE_SLOT_COUNT * 4;

    public void apply(Scratchpad sp) throws Exception {
        List<Scratchpad.Archive> archives = sp.archives();
        if (archives.size() != 2) {
            throw new IllegalStateException("expected 2 Valkyrie 1 ZIP archives, got " + archives.size());
        }

        Scratchpad.Archive soundArchive = null;
        Scratchpad.Archive dataArchive = null;
        for (int i = 0; i < archives.size(); i++) {
            Scratchpad.Archive archive = archives.get(i);
            ArchiveKind kind = classify(archive);
            if (kind == ArchiveKind.SOUND) {
                if (soundArchive != null) throw new IOException("multiple Valkyrie 1 sound archives");
                soundArchive = archive;
            } else if (kind == ArchiveKind.DATA) {
                if (dataArchive != null) throw new IOException("multiple Valkyrie 1 data archives");
                dataArchive = archive;
            } else {
                throw new IOException("unrecognized Valkyrie 1 ZIP archive @" + archive.offset());
            }
        }
        if (soundArchive == null || dataArchive == null) {
            throw new IOException("Valkyrie 1 sound/data archives not found");
        }
        if (soundArchive.offset() >= dataArchive.offset()) {
            throw new IOException("unexpected Valkyrie 1 archive order");
        }

        int soundFrameStart = archiveFrameStart(sp, soundArchive);
        int dataFrameStart = archiveFrameStart(sp, dataArchive);
        if (soundFrameStart >= dataFrameStart) {
            throw new IOException("unexpected Valkyrie 1 archive frame order");
        }

        List<Scratchpad.Resource> gifs = sp.resources(Scratchpad.Kind.GIF);
        if (gifs.isEmpty()) throw new IOException("Valkyrie 1 sprite GIFs not found");
        int firstGif = Integer.MAX_VALUE;
        int lastGifEnd = -1;
        for (int i = 0; i < gifs.size(); i++) {
            Scratchpad.Resource gif = gifs.get(i);
            if (gif.offset() < firstGif) firstGif = gif.offset();
            int end = gif.offset() + gif.length();
            if (end > lastGifEnd) lastGifEnd = end;
        }

        int staticTableBase = firstGif - SPRITE_TABLE_BYTES;
        int dataEnd = dataArchive.offset() + dataArchive.length();
        if (staticTableBase < dataEnd || staticTableBase < 0) {
            throw new IOException("invalid Valkyrie 1 sprite table geometry");
        }
        Scratchpad.Blob table = sp.region(staticTableBase, SPRITE_TABLE_BYTES);
        int maxRelative = validateSpriteTable(sp, table, staticTableBase, firstGif);
        int staticEnd = staticTableBase + maxRelative;
        if (staticEnd != lastGifEnd) {
            throw new IOException("Valkyrie 1 sprite table end " + staticEnd
                    + " does not match final GIF end " + lastGifEnd);
        }
        if (staticEnd > sp.size()) {
            throw new IOException("Valkyrie 1 static resources exceed scratchpad: " + staticEnd
                    + " > " + sp.size());
        }

        // 兩個封存檔都能由解出的項目完整重建，不需要在基線中保留壓縮後的原始位元組。
        soundArchive.omitBaseline();
        dataArchive.omitBaseline();

        // 80..83 是初始化標記。清零後由遊戲自身寫回預設設定。
        sp.state("initialization marker", INITIALIZATION_MARKER, 4).clear();

        // 主存檔固定從 184 開始，右邊界由第一個封存資料框動態決定。
        // 整個槽都清零，連同舊存檔變短後可能殘留的尾部資料一起移除。
        if (SAVE_SLOT_START >= soundFrameStart) {
            throw new IOException("invalid Valkyrie 1 save slot range " + SAVE_SLOT_START
                    + ".." + soundFrameStart);
        }
        sp.state("save slot", SAVE_SLOT_START, soundFrameStart - SAVE_SLOT_START).clear();

        // 184 之前是啟動所需的基底；後續存檔槽由 Runtime 的零基底自然補齊。
        // seed 在結構上不包含主存檔。
        sp.export("sp/seed.bin", sp.region(0, SAVE_SLOT_START));

        // 已知資源組之間均屬於擷取時的可變狀態。
        clearGap(sp, "mutable gap after sound archive",
                soundArchive.offset() + soundArchive.length(), dataFrameStart);
        clearGap(sp, "mutable gap after data archive", dataEnd, staticTableBase);
        clearGap(sp, "scratchpad tail", staticEnd, sp.size());
    }

    private static int archiveFrameStart(Scratchpad sp, Scratchpad.Archive archive) throws IOException {
        int prefix = archive.offset() - 4;
        if (prefix < 0) {
            throw new IOException("Valkyrie 1 ZIP archive has no length prefix @" + archive.offset());
        }
        int declared = sp.region(prefix, 4).i32be(0);
        if (declared != archive.length()) {
            throw new IOException("Valkyrie 1 ZIP archive length prefix @" + prefix + " is "
                    + declared + ", actual " + archive.length());
        }
        return prefix;
    }

    private static ArchiveKind classify(Scratchpad.Archive archive) throws IOException {
        List<Scratchpad.Entry> entries = archive.entries();
        if (entries.size() == 1 && "data.bin".equals(entries.get(0).name())) {
            return ArchiveKind.DATA;
        }
        if (entries.isEmpty()) return ArchiveKind.UNKNOWN;
        for (int i = 0; i < entries.size(); i++) {
            if (!entries.get(i).name().endsWith(".mld")) return ArchiveKind.UNKNOWN;
        }
        return ArchiveKind.SOUND;
    }

    private static int validateSpriteTable(Scratchpad sp, Scratchpad.Blob table,
            int staticTableBase, int firstGif) throws IOException {
        int maxRelative = -1;
        int gifTargets = 0;
        for (int i = 0; i < SPRITE_SLOT_COUNT; i++) {
            int relative = table.i32be(i * 4);
            if (relative < SPRITE_TABLE_BYTES || relative > sp.size() - staticTableBase) {
                throw new IOException("invalid Valkyrie 1 sprite offset at slot " + i + ": " + relative);
            }
            int absolute = staticTableBase + relative;
            Scratchpad.Resource resource = sp.resourceAt(absolute);
            if (resource != null) {
                if (resource.kind() != Scratchpad.Kind.GIF) {
                    throw new IOException("Valkyrie 1 sprite slot " + i + " targets " + resource.kind());
                }
                gifTargets++;
            }
            if (relative > maxRelative) maxRelative = relative;
        }
        if (staticTableBase + SPRITE_TABLE_BYTES != firstGif || gifTargets == 0) {
            throw new IOException("Valkyrie 1 sprite table does not lead into the GIF resource block");
        }
        return maxRelative;
    }

    private static void clearGap(Scratchpad sp, String name, int start, int end) throws IOException {
        if (start < 0 || end < start || end > sp.size()) {
            throw new IOException(name + ": invalid range " + start + ".." + end
                    + " for scratchpad size " + sp.size());
        }
        if (end != start) sp.state(name, start, end - start).clear();
    }

    private enum ArchiveKind { SOUND, DATA, UNKNOWN }
}
