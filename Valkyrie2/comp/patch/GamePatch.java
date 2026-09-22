import java.io.File;

import doja.tools.classfile.ClassFile;

/** Valkyrie 2 的真機幀控制修補。 */
public final class GamePatch {
    private GamePatch() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: GamePatch <game-classes>");
        File root = new File(args[0]);
        File canvas = new File(root, "b.class");

        ClassFile cls = ClassFile.read(canvas);
        int currentTime = cls.findMethodRef("java/lang/System", "currentTimeMillis", "()J");
        int gc = cls.findMethodRef("java/lang/System", "gc", "()V");
        int sleep = cls.findMethodRef("java/lang/Thread", "sleep", "(J)V");
        if (currentTime == 0 || gc == 0 || sleep == 0) {
            throw new IllegalStateException("b timing refs not found");
        }

        ClassFile.Member run = cls.findMethod("run", "()V");
        if (run == null) throw new IllegalStateException("b.run()V not found");
        ClassFile.Code runCode = run.code();
        if (runCode == null) throw new IllegalStateException("b.run()V has no Code attribute");

        int deadClockRemoved = 0;
        int frameGcRemoved = 0;
        for (int p = 0; p < runCode.length();) {
            int next = runCode.next(p);
            if (runCode.opcode(p) == 0xB8) { // 呼叫靜態方法指令
                int ref = runCode.u2(p + 1);
                if (ref == currentTime && next < runCode.length() && runCode.opcode(next) == 0x58) { // 彈出 long 指令
                    // 原程式在主迴圈開頭讀取時間後立即丟棄，直接移除這個浪費資源的無效呼叫。
                    int end = runCode.next(next);
                    runCode.fill(p, end, 0x00);
                    deadClockRemoved++;
                } else if (ref == gc) {
                    // 主迴圈每幀強制 GC 會造成明顯抖動。
                    runCode.fill(p, next, 0x00);
                    frameGcRemoved++;
                }
            }
            p = next;
        }

        require("b.run dead currentTimeMillis", deadClockRemoved, 1);
        require("b.run per-frame System.gc", frameGcRemoved, 1);

        ClassFile.Member wait = cls.findMethod("d", "()V");
        if (wait == null) throw new IllegalStateException("b.d()V not found");
        ClassFile.Code waitCode = wait.code();
        if (waitCode == null) throw new IllegalStateException("b.d()V has no Code attribute");
        require("b.d overdue sleep floor", removeOverdueSleepFloor(waitCode, currentTime, sleep), 1);

        cls.write(canvas);
        System.out.println("Glory2 real-hardware timing patch: original 75/50/25 ms periods kept, "
                + "overdue extra sleep removed, per-frame GC removed");
    }

    /**
     * 原等待函式把負的剩餘時間強制提升到 10 ms。
     * 這裡保留原本的 frame period、100 ms 上限與時間基準，
     * 只把「剩餘時間 <= 0」改成直接跳過 Thread.sleep，避免另設一個 Runtime 計時橋接。
     */
    private static int removeOverdueSleepFloor(ClassFile.Code code, int currentTime, int sleep) throws Exception {
        int floorStart = -1;
        int positivePath = -1;
        int sleepCall = -1;
        int clockUpdate = -1;

        for (int p = 0; p < code.length();) {
            int next = code.next(p);
            int op = code.opcode(p);

            if (op == 0x14) { // 載入 long 常數指令
                int lcmp = next;
                if (lcmp < code.length() && code.opcode(lcmp) == 0x94) { // long 比較指令
                    int branch = code.next(lcmp);
                    if (branch < code.length() && code.opcode(branch) == 0x9C) { // 大於等於零時跳轉指令
                        int constant = code.u2(p + 1);
                        int floorValue = code.next(branch);
                        if (floorValue >= code.length() || code.opcode(floorValue) != 0x14
                                || code.u2(floorValue + 1) != constant) {
                            throw new IllegalStateException("b.d minimum-wait clamp shape changed");
                        }
                        int floorStore = code.next(floorValue);
                        if (floorStore >= code.length() || code.opcode(floorStore) != 0x40) { // 儲存 long 至區域變數指令
                            throw new IllegalStateException("b.d minimum-wait store changed");
                        }
                        int target = branch + (short)code.u2(branch + 1);
                        int afterFloor = code.next(floorStore);
                        if (target != afterFloor) {
                            throw new IllegalStateException("b.d minimum-wait branch changed");
                        }
                        if (floorStart >= 0) throw new IllegalStateException("b.d multiple minimum-wait clamps");
                        floorStart = p;
                        positivePath = target;
                    }
                }
            } else if (op == 0xB8) { // 呼叫靜態方法指令
                int ref = code.u2(p + 1);
                if (ref == sleep) {
                    if (sleepCall >= 0) throw new IllegalStateException("b.d multiple Thread.sleep calls");
                    sleepCall = p;
                } else if (ref == currentTime && sleepCall >= 0 && clockUpdate < 0) {
                    clockUpdate = p;
                }
            }
            p = next;
        }

        if (floorStart < 0 || positivePath < 0 || sleepCall < 0 || clockUpdate < 0) return 0;
        if (positivePath - floorStart < 6) throw new IllegalStateException("b.d minimum-wait clamp too short");

        // dup2 + lstore_1 後，運算棧上仍保留一份 wait；直接用它和 0 比較，避免在合流點留下 long。
        // wait <= 0 時直接跳到幀時間戳更新；wait > 0 時沿用原本的 100 ms 上限與 sleep 路徑。
        code.putByte(floorStart, 0x09);     // 載入 long 常數 0
        code.putByte(floorStart + 1, 0x94); // long 比較
        code.putByte(floorStart + 2, 0x9E); // 小於等於零時跳轉
        int branchOffset = clockUpdate - (floorStart + 2);
        if (branchOffset < Short.MIN_VALUE || branchOffset > Short.MAX_VALUE) {
            throw new IllegalStateException("b.d overdue branch out of range");
        }
        code.putU2(floorStart + 3, branchOffset & 0xFFFF);
        code.fill(floorStart + 5, positivePath, 0x00);
        return 1;
    }

    private static void require(String label, int actual, int expected) {
        if (actual != expected) {
            throw new IllegalStateException(label + ": expected " + expected + ", got " + actual);
        }
    }
}
