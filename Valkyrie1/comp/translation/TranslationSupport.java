package translation;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import doja.tools.classfile.ClassFile;
import doja.tools.font.FontUsage;
import doja.tools.io.FileIO;
import doja.tools.io.FileTree;
import doja.tools.translation.ResolvedText;
import doja.tools.translation.TextOccurrence;
import doja.tools.translation.TranslationTable;

final class TranslationSupport {
    static final int CONTROL_FFFF = 0xFFFF;
    static final int CONTROL_FF00 = 0xFF00;
    static final int IDEOGRAPHIC_SPACE = 0x3000;

    private TranslationSupport() {}

    static final class Config {
        final String[] classNames;
        final int spSection;
        final int spTextStart;

        Config(String[] classNames, int spSection, int spTextStart) {
            this.classNames = classNames;
            this.spSection = spSection;
            this.spTextStart = spTextStart;
        }

        static Config read(File file) throws IOException {
            Properties properties = new Properties();
            FileInputStream input = new FileInputStream(file);
            try {
                properties.load(input);
            } finally {
                input.close();
            }
            String classes = required(properties, "translation.classes");
            String[] raw = classes.split(",");
            List<String> names = new ArrayList<String>();
            for (int i = 0; i < raw.length; i++) {
                String name = raw[i].trim();
                if (name.length() != 0) names.add(name);
            }
            if (names.isEmpty()) throw new IOException(file + ": translation.classes is empty");
            return new Config(names.toArray(new String[names.size()]),
                    integer(file, properties, "translation.sp.section"),
                    integer(file, properties, "translation.sp.text.start"));
        }

        private static String required(Properties properties, String key) throws IOException {
            String value = properties.getProperty(key);
            if (value == null || value.trim().length() == 0) throw new IOException("missing " + key);
            return value.trim();
        }

        private static int integer(File file, Properties properties, String key) throws IOException {
            try {
                return Integer.parseInt(required(properties, key));
            } catch (NumberFormatException ex) {
                throw new IOException(file + ": invalid " + key);
            }
        }
    }

    static SpModel extractSpModel(File assetsDir, Config config) throws IOException {
        File dataFile = findSingleDataBin(assetsDir);
        return SpModel.read(FileIO.read(dataFile), config, dataFile);
    }

    static String resolvedText(TranslationTable translations, String source) throws IOException {
        String translated = translations.resolve(source);
        if (translated.indexOf('\u0000') >= 0 || translated.indexOf((char)CONTROL_FFFF) >= 0
                || translated.indexOf((char)CONTROL_FF00) >= 0) {
            throw new IOException("translation contains reserved control character for: " + source);
        }
        return translated;
    }

    /** 保留最終執行期類別文字的字形涵蓋範圍。 */
    static void addClassGlyphs(File classesDir, FontUsage usage) throws IOException {
        List<File> files = FileTree.filesWithSuffix(classesDir, ".class");
        for (int i = 0; i < files.size(); i++) {
            ClassFile cls = ClassFile.read(files.get(i));
            List<Integer> constants = cls.stringConstants();
            for (int j = 0; j < constants.size(); j++) {
                usage.addRenderText(cls.string(constants.get(j).intValue()));
            }
        }
    }

    static final class SpModel {
        final byte[] data;
        final File dataFile;
        final int poolOffset;
        final int poolUnits;
        final int textStart;
        final List<Run> runs;
        final List<TextOccurrence> entries;

        SpModel(byte[] data, File dataFile, int poolOffset, int poolUnits, int textStart,
                List<Run> runs, List<TextOccurrence> entries) {
            this.data = data;
            this.dataFile = dataFile;
            this.poolOffset = poolOffset;
            this.poolUnits = poolUnits;
            this.textStart = textStart;
            this.runs = runs;
            this.entries = entries;
        }

        static SpModel read(byte[] data, Config config, File dataFile) throws IOException {
            if (data.length < 40) throw new IOException(dataFile + ": data.bin too small");
            int count = be32(data, 32);
            int table = be32(data, 36);
            if (config.spSection < 0 || config.spSection >= count || count <= 0 || count > 32
                    || table < 0 || table + count * 8 > data.length) {
                throw new IOException(dataFile + ": invalid section table");
            }
            int length = be32(data, table + config.spSection * 8);
            int offset = be32(data, table + config.spSection * 8 + 4);
            if (length < 0 || (length & 1) != 0 || offset < 0 || offset + length > data.length) {
                throw new IOException(dataFile + ": invalid text section");
            }
            int units = length / 2;
            int start = config.spTextStart;
            if (start < 0 || start >= units) {
                throw new IOException(dataFile + ": invalid translation.sp.text.start");
            }

            List<Run> runs = new ArrayList<Run>();
            List<TextOccurrence> entries = new ArrayList<TextOccurrence>();
            int p = start;
            while (p < units) {
                while (p < units && u16le(data, offset + p * 2) == 0) p++;
                if (p >= units) break;
                int runStart = p;
                StringBuffer raw = new StringBuffer();
                while (p < units) {
                    int c = u16le(data, offset + p * 2);
                    if (c == 0) break;
                    raw.append((char)c);
                    p++;
                }
                Run run = new Run(runStart, raw.toString());
                splitRun(run, entries);
                runs.add(run);
            }
            if (runs.isEmpty()) throw new IOException(dataFile + ": no SP text found at P" + start);
            return new SpModel(data, dataFile, offset, units, start, runs, entries);
        }

        private static void splitRun(Run run, List<TextOccurrence> entries) {
            int i = 0;
            while (i < run.original.length()) {
                char c = run.original.charAt(i);
                if (isControl(c)) {
                    run.pieces.add(Piece.fixed(i, String.valueOf(c)));
                    i++;
                    continue;
                }
                int start = i;
                while (i < run.original.length() && !isControl(run.original.charAt(i))) i++;
                int end = i;
                int bodyStart = start;
                while (bodyStart < end && run.original.charAt(bodyStart) == IDEOGRAPHIC_SPACE) bodyStart++;
                int bodyEnd = end;
                while (bodyEnd > bodyStart && run.original.charAt(bodyEnd - 1) == IDEOGRAPHIC_SPACE) bodyEnd--;
                if (bodyStart > start) run.pieces.add(Piece.fixed(start, run.original.substring(start, bodyStart)));
                if (bodyEnd > bodyStart) {
                    String source = run.original.substring(bodyStart, bodyEnd);
                    run.pieces.add(Piece.text(bodyStart, source));
                    entries.add(new TextOccurrence("sp", "", run.start + bodyStart, source));
                }
                if (bodyEnd < end) run.pieces.add(Piece.fixed(bodyEnd, run.original.substring(bodyEnd, end)));
            }
        }

        private static boolean isControl(char c) {
            return c == CONTROL_FFFF || c == CONTROL_FF00;
        }

        boolean hasChangedTranslation(TranslationTable translations) throws IOException {
            for (int i = 0; i < entries.size(); i++) {
                TextOccurrence entry = entries.get(i);
                if (!resolvedText(translations, entry.source).equals(entry.source)) return true;
            }
            return false;
        }

        void apply(TranslationTable translations, List<ResolvedText> resolved) throws IOException {
            if (!hasChangedTranslation(translations)) {
                for (int r = 0; r < runs.size(); r++) {
                    Run run = runs.get(r);
                    for (int p = 0; p < run.pieces.size(); p++) {
                        Piece piece = run.pieces.get(p);
                        if (!piece.translatable) continue;
                        resolved.add(new ResolvedText("sp", "", run.start + piece.oldOffset, piece.text));
                    }
                }
                return;
            }

            Piece[] owner = new Piece[poolUnits - textStart];
            Run[] runOwner = new Run[poolUnits - textStart];
            for (int r = 0; r < runs.size(); r++) {
                Run run = runs.get(r);
                for (int j = 0; j < run.original.length(); j++) runOwner[run.start + j - textStart] = run;
                for (int p = 0; p < run.pieces.size(); p++) {
                    Piece piece = run.pieces.get(p);
                    for (int j = 0; j < piece.text.length(); j++) {
                        owner[run.start + piece.oldOffset + j - textStart] = piece;
                    }
                }
            }

            int cursor = textStart;
            int firstZero = -1;
            for (int r = 0; r < runs.size(); r++) {
                Run run = runs.get(r);
                run.newStart = cursor;
                for (int p = 0; p < run.pieces.size(); p++) {
                    Piece piece = run.pieces.get(p);
                    piece.newStart = cursor;
                    String output = piece.translatable ? resolvedText(translations, piece.text) : piece.text;
                    piece.output = output;
                    cursor += output.length();
                }
                if (cursor >= poolUnits) throw new IOException("translated SP text exceeds data.bin capacity");
                if (firstZero < 0) firstZero = cursor;
                cursor++;
            }
            if (cursor > poolUnits || firstZero < 0) {
                throw new IOException("translated SP text exceeds data.bin capacity");
            }

            int replacements = 0;
            for (int i = 0; i < textStart; i++) {
                int pos = poolOffset + i * 2;
                int old = u16le(data, pos);
                if (old < textStart || old >= poolUnits) continue;
                int replacement;
                int oldValue = u16le(data, poolOffset + old * 2);
                if (oldValue == 0) {
                    replacement = firstZero;
                } else {
                    Piece piece = owner[old - textStart];
                    Run run = runOwner[old - textStart];
                    if (piece == null || run == null) {
                        throw new IOException("P" + old + ": reference does not point into a known SP text run");
                    }
                    int oldPieceStart = run.start + piece.oldOffset;
                    int relative = old - oldPieceStart;
                    if (relative == 0) {
                        replacement = piece.newStart;
                    } else if (!piece.translatable || piece.output.equals(piece.text)) {
                        replacement = piece.newStart + relative;
                    } else {
                        throw new IOException("P" + old + ": reference points inside translated text '"
                                + piece.text + "'; split this text at the referenced position before translating");
                    }
                }
                if (replacement != old) {
                    putU16le(data, pos, replacement);
                    replacements++;
                }
            }

            for (int i = textStart; i < poolUnits; i++) putU16le(data, poolOffset + i * 2, 0);
            for (int r = 0; r < runs.size(); r++) {
                Run run = runs.get(r);
                int q = run.newStart;
                for (int p = 0; p < run.pieces.size(); p++) {
                    Piece piece = run.pieces.get(p);
                    for (int j = 0; j < piece.output.length(); j++) {
                        putU16le(data, poolOffset + q * 2, piece.output.charAt(j));
                        q++;
                    }
                    if (piece.translatable) {
                        resolved.add(new ResolvedText("sp", "", piece.newStart, piece.output));
                    }
                }
            }
            FileIO.write(dataFile, data);
            System.out.println("TranslationApply: rebuilt Valkyrie 1 SP text pool, repaired "
                    + replacements + " reference(s)");
        }
    }

    static final class Run {
        final int start;
        final String original;
        final List<Piece> pieces = new ArrayList<Piece>();
        int newStart;

        Run(int start, String original) {
            this.start = start;
            this.original = original;
        }
    }

    static final class Piece {
        final int oldOffset;
        final String text;
        final boolean translatable;
        int newStart;
        String output;

        Piece(int oldOffset, String text, boolean translatable) {
            this.oldOffset = oldOffset;
            this.text = text;
            this.translatable = translatable;
        }

        static Piece fixed(int offset, String text) { return new Piece(offset, text, false); }
        static Piece text(int offset, String text) { return new Piece(offset, text, true); }
    }

    static File findSingleDataBin(File root) throws IOException {
        List<File> files = new ArrayList<File>();
        collectDataBin(root, files);
        if (files.size() != 1) {
            throw new IOException("expected exactly one data.bin under " + root + ", found " + files.size());
        }
        return files.get(0);
    }

    private static void collectDataBin(File file, List<File> result) {
        if (file == null || !file.exists()) return;
        if (file.isFile()) {
            if ("data.bin".equalsIgnoreCase(file.getName())) result.add(file);
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (int i = 0; i < children.length; i++) collectDataBin(children[i], result);
        }
    }

    static int u16le(byte[] data, int p) {
        return (data[p] & 255) | ((data[p + 1] & 255) << 8);
    }

    static void putU16le(byte[] data, int p, int value) {
        data[p] = (byte)value;
        data[p + 1] = (byte)(value >>> 8);
    }

    static int be32(byte[] data, int p) {
        return ((data[p] & 255) << 24) | ((data[p + 1] & 255) << 16)
                | ((data[p + 2] & 255) << 8) | (data[p + 3] & 255);
    }
}
