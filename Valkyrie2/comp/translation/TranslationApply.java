package translation;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import doja.tools.font.FontUsage;
import doja.tools.translation.ClassText;
import doja.tools.translation.ResolvedText;
import doja.tools.translation.ResolvedTextTable;
import doja.tools.translation.TextOccurrence;
import doja.tools.translation.TranslationTable;

public final class TranslationApply {
    private TranslationApply() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Usage: TranslationApply <game-classes> <generated-assets> "
                            + "<config.properties> <Translation.tsv> <resolved.tsv>");
        }
        File classes = new File(args[0]);
        File assets = new File(args[1]);
        TranslationSupport.Config config = TranslationSupport.Config.read(new File(args[2]));
        TranslationTable translations = TranslationTable.read(new File(args[3]));
        File resolvedFile = new File(args[4]);

        List<ResolvedText> resolved = new ArrayList<ResolvedText>();
        List<TextOccurrence> classEntries = ClassText.extractDirectory(classes, config.classNames);
        Map<String,Map<Integer,String>> patches = ClassText.newPatchMap();
        for (int i = 0; i < classEntries.size(); i++) {
            TextOccurrence entry = classEntries.get(i);
            String text = TranslationSupport.resolvedText(translations, entry.source);
            resolved.add(new ResolvedText("class", entry.file, entry.location, text));
            if (!text.equals(entry.source)) {
                ClassText.addPatch(patches, entry.file, entry.location, text);
            }
        }
        ClassText.patchDirectory(classes, patches);

        TranslationSupport.SpModel sp = TranslationSupport.extractSpModel(assets, config);
        sp.apply(translations, resolved);
        ResolvedTextTable.write(resolvedFile, resolved);

        FontUsage usage = new FontUsage();
        for (int i = 0; i < resolved.size(); i++) usage.addRenderText(resolved.get(i).text);
        usage.addRenderText(String.valueOf((char)TranslationSupport.IDEOGRAPHIC_SPACE));
        usage.write(new File(assets.getParentFile(), "font-usage.bin"));

        System.out.println("TranslationApply: " + translations.translatedCount() + " translated source text(s), "
                + resolved.size() + " resolved occurrence(s); font usage emitted");
    }
}
