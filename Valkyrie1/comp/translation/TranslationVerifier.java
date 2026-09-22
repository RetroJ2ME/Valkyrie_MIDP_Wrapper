package translation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import doja.tools.io.FileIO;
import doja.tools.translation.ClassText;
import doja.tools.translation.ResolvedText;
import doja.tools.translation.ResolvedTextTable;

public final class TranslationVerifier {
    private TranslationVerifier() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: TranslationVerifier <raw.jar> <resolved.tsv> <config.properties> <generated-assets>");
        }
        File jarFile = new File(args[0]);
        List<ResolvedText> resolved = ResolvedTextTable.read(new File(args[1]));
        TranslationSupport.Config config = TranslationSupport.Config.read(new File(args[2]));
        byte[] expectedData = FileIO.read(TranslationSupport.findSingleDataBin(new File(args[3])));
        ZipFile jar = new ZipFile(jarFile);
        try {
            Map<String,byte[]> classes = new HashMap<String,byte[]>();
            byte[] dataBin = null;
            for (int i = 0; i < resolved.size(); i++) {
                ResolvedText entry = resolved.get(i);
                if ("class".equals(entry.kind)) {
                    byte[] bytes = classes.get(entry.file);
                    if (bytes == null) {
                        ZipEntry zipEntry = jar.getEntry(entry.file);
                        if (zipEntry == null) throw new IOException("final JAR has no " + entry.file);
                        bytes = readAll(jar, zipEntry);
                        classes.put(entry.file, bytes);
                    }
                    ClassText.verify(bytes, entry.location, entry.text, entry.file + ":" + entry.location);
                } else if ("sp".equals(entry.kind)) {
                    if (dataBin == null) dataBin = findDataBin(jar);
                    verifySp(dataBin, config.spSection, entry.location, entry.text);
                } else {
                    throw new IOException("unknown resolved kind " + entry.kind);
                }
            }
            if (dataBin == null) dataBin = findDataBin(jar);
            if (!Arrays.equals(expectedData, dataBin)) {
                throw new IOException("final JAR data.bin differs from generated translated data.bin");
            }
            System.out.println("TranslationVerifier: verified " + resolved.size()
                    + " text occurrence(s) and packaged data.bin");
        } finally {
            jar.close();
        }
    }

    private static void verifySp(byte[] data, int section, int pIndex, String expected) throws IOException {
        if (data.length < 40) throw new IOException("final data.bin too small");
        int count = TranslationSupport.be32(data, 32);
        int table = TranslationSupport.be32(data, 36);
        if (section < 0 || section >= count || table < 0 || table + count * 8 > data.length) {
            throw new IOException("invalid final data.bin sections");
        }
        int length = TranslationSupport.be32(data, table + section * 8);
        int offset = TranslationSupport.be32(data, table + section * 8 + 4);
        if ((length & 1) != 0 || pIndex < 0 || pIndex + expected.length() > length / 2) {
            throw new IOException("SP resolved location outside text pool: P" + pIndex);
        }
        for (int i = 0; i < expected.length(); i++) {
            int actual = TranslationSupport.u16le(data, offset + (pIndex + i) * 2);
            if (actual != expected.charAt(i)) throw new IOException("SP mismatch at P" + pIndex + "+" + i);
        }
    }

    private static byte[] findDataBin(ZipFile jar) throws IOException {
        byte[] found = null;
        Enumeration<? extends ZipEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith("assets/") && name.endsWith("/data.bin")) {
                if (found != null) throw new IOException("final JAR has multiple data.bin files");
                found = readAll(jar, entry);
            }
        }
        if (found == null) throw new IOException("final JAR has no data.bin");
        return found;
    }

    private static byte[] readAll(ZipFile zip, ZipEntry entry) throws IOException {
        InputStream input = zip.getInputStream(entry);
        try {
            return FileIO.read(input);
        } finally {
            input.close();
        }
    }
}
