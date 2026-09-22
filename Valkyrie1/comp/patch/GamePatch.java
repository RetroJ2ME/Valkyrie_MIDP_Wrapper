import java.io.File;
import java.io.IOException;
import java.util.List;

import doja.tools.classfile.ClassFile;
import doja.tools.io.FileTree;

/** 從 G5 MainLoopPatch 還原的 Valkyrie 1 熱迴圈清理修補。 */
public final class GamePatch {
    private static final String SYSTEM = "java/lang/System";

    private GamePatch() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: GamePatch <game-classes>");
        File root = new File(args[0]);
        if (!root.isDirectory()) throw new IOException("not a directory: " + root);

        List<File> files = FileTree.filesWithSuffix(root, ".class");
        int gcCalls = 0;
        int discardedClockCalls = 0;
        int canvasClasses = 0;

        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            ClassFile cls = ClassFile.read(file);
            int changed = 0;

            int gcRef = cls.findMethodRef(SYSTEM, "gc", "()V");
            if (gcRef != 0) {
                int removed = removeInvokestatic(cls, gcRef);
                gcCalls += removed;
                changed += removed;
            }

            if ("i".equals(cls.className())) {
                canvasClasses++;
                int clockRef = cls.findMethodRef(SYSTEM, "currentTimeMillis", "()J");
                ClassFile.Member run = cls.findMethod("run", "()V");
                if (clockRef == 0 || run == null || run.code() == null) {
                    throw new IOException("Valkyrie 1 Canvas.run() clock call not found");
                }
                int removed = removeDiscardedClock(run.code(), clockRef);
                discardedClockCalls += removed;
                changed += removed;
            }

            if (changed != 0) cls.write(file);
        }

        if (canvasClasses != 1) {
            throw new IOException("expected exactly one Valkyrie 1 Canvas class i, got " + canvasClasses);
        }
        if (gcCalls != 49) {
            throw new IOException("unexpected Valkyrie 1 System.gc() call count: " + gcCalls + " (expected 49)");
        }
        if (discardedClockCalls != 1) {
            throw new IOException("unexpected Valkyrie 1 discarded currentTimeMillis count: "
                    + discardedClockCalls + " (expected 1)");
        }

        System.out.println("GamePatch: removed System.gc=" + gcCalls
                + ", discarded currentTimeMillis=" + discardedClockCalls);
    }

    private static int removeInvokestatic(ClassFile cls, int methodRef) throws IOException {
        int changed = 0;
        List<ClassFile.Member> methods = cls.methods();
        for (int m = 0; m < methods.size(); m++) {
            ClassFile.Code code = methods.get(m).code();
            if (code == null) continue;
            for (int p = 0; p < code.length();) {
                int next = code.next(p);
                if (code.opcode(p) == 0xB8 && code.u2(p + 1) == methodRef) {
                    code.fill(p, p + 3, 0);
                    changed++;
                }
                p = next;
            }
        }
        return changed;
    }

    private static int removeDiscardedClock(ClassFile.Code code, int methodRef) throws IOException {
        int changed = 0;
        for (int p = 0; p < code.length();) {
            int next = code.next(p);
            if (code.opcode(p) == 0xB8 && code.u2(p + 1) == methodRef) {
                if (next >= code.length() || code.opcode(next) != 0x58) {
                    throw new IOException("Valkyrie 1 Canvas.run() currentTimeMillis result is not discarded");
                }
                code.fill(p, next + 1, 0);
                changed++;
            }
            p = next;
        }
        return changed;
    }
}
