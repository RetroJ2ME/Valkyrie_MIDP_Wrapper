import doja.tools.scratchpad.Scratchpad;

/** Valkyrie 2 的 Scratchpad 佈局。 */
public final class GameScratchpad implements Scratchpad.Schema {
    public void apply(Scratchpad sp) throws Exception {
        // 保留擷取到的設定區塊，如遊戲速度（一般為 N）。
        // 速度選擇與持久化由遊戲負責。

        // 在 Scratchpad 擷取的進度／解鎖狀態。
        sp.state("game progress", 132, 4).clear();

        // 遊戲清除資料流程使用的獨立可變旗標。
        sp.state("clear-data flag", 200, 1).clear();
    }
}
