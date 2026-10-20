package com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules;

import com.github.tartaricacid.touhoulittlemaid.vn.controller.VisualNovelEngine;
import com.github.tartaricacid.touhoulittlemaid.vn.state.VnState;

/**
 * Scene lifecycle bookkeeping.
 * <p>
 * This class used to own the whole save system: it appended a snapshot of every
 * variable map to a {@code List<Map>} on each finish and wrote it to
 * {@code saves/<world>/<player>/<entity>.json}, keyed by entity display name.
 * <p>
 * All of that is gone. Durable state lives in {@link VnState} — WORLD in a
 * profile JSON file, MAID in entity NBT — and is written through as variables
 * change. There is nothing left to save here.
 * <p>
 * The old implementation threw {@code NullPointerException: vn.globalSave is
 * null} on finish, because the fold's new constructor no longer creates the
 * legacy save lists. That is what crashed the first successful scene run.
 */
public class SaveHandler {

    private SaveHandler() {
    }

    /**
     * End the scene cleanly: stop the engine, flush pending world writes, tell
     * the screen to close. A failed flush must never stop the scene closing.
     */
    public static void processFinishing(VisualNovelEngine vn) {
        vn.isEngineRunning.set(false);
        try {
            VnState.flush();
        } catch (Exception ignored) {
            // Intentionally swallowed: closing the screen matters more.
        }
        vn.shutdown.set(true);
    }

    /**
     * Legacy no-op, kept so older call paths compile. State is read from
     * {@link VnState} on demand instead of from snapshot lists.
     */
    @Deprecated
    public static void loadProgress(VisualNovelEngine vn) {
        // Intentional no-op. See class javadoc.
    }
}
