package com.github.tartaricacid.touhoulittlemaid.vn.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.AbstractMaidContext;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.vn.item.VnTalkItem;
import com.github.tartaricacid.touhoulittlemaid.vn.state.SceneRegistry;
import com.github.tartaricacid.touhoulittlemaid.vn.state.VnState;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tells the LLM about her own story state.
 * <p>
 * TLM exposes health, sleep, follow, schedule and so on but has no notion of
 * affection or story progress, so the maid currently cannot react to being at
 * tier 3 of a relationship. These contexts close that gap: the same variables the
 * VN writes are the ones the prompt reads, which is what makes the two layers
 * feel like one character.
 * <p>
 * Registered as {@code promptContext = true} (always injected) because a
 * character who does not know how much she trusts you talks like a stranger.
 */
public final class VnStateContext {

    public static final String CATEGORY = "vn";
    private static final String SUMMARY = "Visual novel progress: affection and seen scenes";

    private VnStateContext() {
    }

    public static void registerAll(GameContextRegister register) {
        register.registerCategory(CATEGORY, SUMMARY, true);
        register.registerContext(CATEGORY, new AffectionContext());
        register.registerContext(CATEGORY, new SceneProgressContext());
    }

    /**
     * Affection toward this player. Read from the WORLD scope, which persists
     * across scenes and worlds.
     */
    private static final class AffectionContext extends AbstractMaidContext {
        private AffectionContext() {
            super("affection", "Affection toward the player");
        }

        @Override
        public String getValue(EntityMaid maid) {
            String id = VnTalkItem.characterIdOf(maid);
            int affection = VnState.getInt(
                    com.github.tartaricacid.touhoulittlemaid.vn.state.VnScope.WORLD,
                    "affection." + id, 0);
            return affection + " (" + tierName(affection) + ")";
        }

        private String tierName(int affection) {
            if (affection >= 384) return "devoted";
            if (affection >= 192) return "close";
            if (affection >= 64) return "friendly";
            return "distant";
        }
    }

    /**
     * Which scenes exist for her and which she has already lived through, so she
     * does not repeat a first meeting or offer a scene she has already played.
     */
    private static final class SceneProgressContext extends AbstractMaidContext {
        private SceneProgressContext() {
            super("scene_progress", "Scenes available and already seen");
        }

        @Override
        public String getValue(EntityMaid maid) {
            String id = VnTalkItem.characterIdOf(maid);
            String available = SceneRegistry.listIds().stream()
                    .filter(s -> s.startsWith(id + "/"))
                    .collect(Collectors.joining(", "));
            if (available.isEmpty()) {
                return "no scenes installed for this maid";
            }
            Map<String, Object> maidVars = snapshotMaid(maid);
            String seen = maidVars.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("seen."))
                    .filter(e -> Boolean.TRUE.equals(e.getValue()) || "true".equalsIgnoreCase(String.valueOf(e.getValue())))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.joining(", "));
            return "available: [" + available + "]; already seen: ["
                    + (seen.isEmpty() ? "none" : seen) + "]";
        }

        private Map<String, Object> snapshotMaid(EntityMaid maid) {
            var tag = maid.getPersistentData().getCompound(VnState.NBT_KEY);
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (String key : tag.getAllKeys()) {
                out.put(key, tag.getString(key));
            }
            return out;
        }
    }
}
