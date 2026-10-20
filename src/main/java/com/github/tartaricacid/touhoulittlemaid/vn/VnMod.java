package com.github.tartaricacid.touhoulittlemaid.vn;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;

/**
 * Holder for the Visual Novel subsystem's identifiers.
 * <p>
 * This replaces the old {@code MobTalkerRedux} class (which was a whole
 * {@code @Mod} entry point in the original standalone mod). Since the VN
 * engine is now folded into Touhou Little Maid, there is no separate mod id —
 * this class just exposes the two strings the rest of the engine needs.
 * <p>
 * KEEP THIS CONTENT-NEUTRAL. Nothing in the {@code vn} package may reference
 * adult content; the jar ships as a clean framework.
 */
public final class VnMod {
    /**
     * Resource namespace for VN assets (textures live at
     * {@code assets/touhou_little_maid/textures/vn/...}).
     */
    public static final String MODID = TouhouLittleMaid.MOD_ID;

    /**
     * Config / data folder name for VN content that lives outside the jar:
     * {@code config/<VN_NAMESPACE>/...}
     */
    public static final String VN_NAMESPACE = "tlm_vn";

    public static final String VN_VERSION = "0.1.0";

    private VnMod() {
    }

    public static void log(String msg) {
        TouhouLittleMaid.LOGGER.info("[TLM-VN] {}", msg);
    }

    public static void warn(String msg) {
        TouhouLittleMaid.LOGGER.warn("[TLM-VN] {}", msg);
    }

    public static void error(String msg, Throwable t) {
        TouhouLittleMaid.LOGGER.error("[TLM-VN] {}", msg, t);
    }
}
