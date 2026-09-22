package translation;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import doja.tools.translation.ClassText;
import doja.tools.translation.TextIndex;
import doja.tools.translation.TextOccurrence;
import doja.tools.translation.TranslationTable;

public final class TranslationPrepare {
    private TranslationPrepare() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Usage: TranslationPrepare <game.jar> <generated-assets> "
                            + "<config.properties> <Index.tsv> <Translation.tsv>");
        }
        File jar = new File(args[0]);
        File assets = new File(args[1]);
        TranslationSupport.Config config = TranslationSupport.Config.read(new File(args[2]));
        File index = new File(args[3]);
        File translations = new File(args[4]);

        List<TextOccurrence> all = new ArrayList<TextOccurrence>();
        all.addAll(ClassText.extractJar(jar, config.classNames));
        TranslationSupport.SpModel sp = TranslationSupport.extractSpModel(assets, config);
        all.addAll(sp.entries);

        List<TextIndex.Entry> indexEntries = new ArrayList<TextIndex.Entry>();
        int classId = 0;
        int spId = 0;
        for (int i = 0; i < all.size(); i++) {
            TextOccurrence entry = all.get(i);
            boolean classText = "class".equals(entry.kind);
            String id = format(classText ? 'C' : 'S', classText ? ++classId : ++spId);
            String source = classText ? "JAR" : "SP";
            String location = classText ? entry.file + ":" + entry.location : "P" + entry.location;
            indexEntries.add(new TextIndex.Entry(id, source, location, entry.source));
        }
        TextIndex.write(index, indexEntries);

        Set<String> seen = new LinkedHashSet<String>();
        List<String> unique = new ArrayList<String>();
        for (int i = 0; i < all.size(); i++) {
            String source = all.get(i).source;
            if (seen.add(source)) unique.add(source);
        }
        TranslationTable table = TranslationTable.read(translations);
        table.write(translations, unique);
        System.out.println("TranslationPrepare: " + all.size() + " indexed occurrence(s), "
                + unique.size() + " unique source text(s); blank translations fall back to original text");
    }

    private static String format(char prefix, int value) {
        String text = String.valueOf(value);
        while (text.length() < 4) text = "0" + text;
        return prefix + text;
    }
}
