package com.github.tartaricacid.touhoulittlemaid.vn.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.vn.VnMod;
import com.github.tartaricacid.touhoulittlemaid.vn.state.SceneLauncher;
import com.github.tartaricacid.touhoulittlemaid.vn.state.SceneRegistry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.Set;

/**
 * The bridge: lets the maid's LLM open a visual novel scene.
 * <p>
 * This is the entire point of folding the VN engine into Touhou Little Maid.
 * Authored scenes stay authored (control, art, pacing); the model gets to decide
 * <em>when</em> one happens. At the moment the scene opens the chat is over, so
 * the tool returns immediately and the screen takes over.
 * <p>
 * Content-neutral: it will happily open any scene id the content pack installed.
 */
public class PlaySceneTool implements ITool<PlaySceneTool.Result> {
    public static final String TOOL_ID = "play_scene";

    private static final String SCENE_PARAM_ID = "scene_id";

    private static final String TOOL_DESC = """
            Open a visual novel scene with the player. Only use this when the moment genuinely calls for it:
            a story beat, a confession, a private moment at night, or when the player asks to see a scene.
            Pass a scene id from the available list. Do NOT use this for ordinary conversation.
            """.trim();

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(Codec.STRING.fieldOf(SCENE_PARAM_ID).forGetter(Result::sceneId))
                    .apply(instance, Result::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return TOOL_DESC;
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter sceneId = StringParameter.create();
        sceneId.setDescription("Scene id to play, e.g. cupa/main");
        // Only scenes this maid has actually unlocked. The enum IS the gate:
        // the model cannot choose what it cannot see, so there is nothing to
        // refuse and no retry loop.
        Set<String> ids = SceneRegistry.listUnlockedFor(maid);
        if (!ids.isEmpty()) {
            sceneId.addEnumValues(ids.toArray(new String[0]));
        }
        root.addProperties(SCENE_PARAM_ID, sceneId);
        return root;
    }

    /**
     * Hide the tool entirely when she has nothing unlocked. A maid with no
     * available scenes should not be offered a scene button at all.
     */
    @Override
    public boolean trigger(EntityMaid maid, ChatCompletion chatCompletion) {
        return !SceneRegistry.listUnlockedFor(maid).isEmpty();
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        String sceneId = result.sceneId();

        if (!SceneRegistry.exists(sceneId)) {
            Set<String> ids = SceneRegistry.listIds();
            String available = ids.isEmpty() ? "(none installed)" : String.join(", ", ids);
            VnMod.warn("LLM asked for missing scene '" + sceneId + "'");
            return callback.addToolResult(
                    "Error: no scene with id '" + sceneId + "'. Available: " + available, toolId);
        }

        boolean opened = SceneLauncher.open(maid, sceneId);
        if (!opened) {
            return callback.addToolResult("Error: could not open scene '" + sceneId + "'.", toolId);
        }

        // The scene owns the screen from here; the chat turn ends.
        return callback.addToolResult(
                "Scene '" + sceneId + "' started. Stop talking and let the scene play.", toolId);
    }

    @Override
    public Component invocationSummaryComponent(Result result) {
        return Component.literal("Play Scene: " + result.sceneId()).withStyle(ChatFormatting.GRAY);
    }

    public record Result(String sceneId) {
    }
}
