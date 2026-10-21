package com.github.tartaricacid.touhoulittlemaid.vn.state;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.vn.VnMod;
import com.github.tartaricacid.touhoulittlemaid.vn.controller.VisualNovelEngine;
import com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules.PlayerInventoryHandler;
import com.github.tartaricacid.touhoulittlemaid.vn.view.DialogueScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Turns "open scene X on maid Y" into a running {@link DialogueScreen}.
 * <p>
 * This is the seam the whole project hinges on: one entry point that
 * <ul>
 *     <li>loads a scene from {@link SceneRegistry},</li>
 *     <li>injects live world state (day/night, affection) as VN variables exactly
 *         like the browser viewer's {@code globals} option,</li>
 *     <li>attaches the maid's persistent NBT to {@link VnState}, and</li>
 *     <li>opens the screen on the client thread.</li>
 * </ul>
 * Both the right-click item and the {@code play_scene} AI tool call this, so
 * authored scenes and LLM-triggered scenes behave identically.
 */
public final class SceneLauncher {

    /**
     * Live values injected into the scene scope under these names before the
     * engine runs. Scripts can branch on {@code isDay} / {@code isNight} without
     * the engine needing to know about Minecraft.
     */
    public static final String VAR_IS_DAY = "isDay";
    public static final String VAR_IS_NIGHT = "isNight";

    /**
     * Item id the player was holding when the scene opened, e.g.
     * {@code minecraft:end_rod}, or {@code minecraft:air} for empty hands.
     * Lets the spine react to a gift without any engine-side item registry.
     */
    public static final String VAR_HELD_ITEM = "heldItem";

    private SceneLauncher() {
    }

    /**
     * Open a scene on the given maid. Thread-safe: hops to the client thread.
     *
     * @return true if a scene was found and is opening
     */
    public static boolean open(EntityMaid maid, String sceneId) {
        if (maid == null) {
            VnMod.warn("open() called with null maid for scene " + sceneId);
            return false;
        }
        if (!SceneRegistry.exists(sceneId)) {
            VnMod.warn("Scene not found: " + sceneId);
            return false;
        }
        List<Map<String, Object>> script = SceneRegistry.load(sceneId);
        if (script.isEmpty()) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            return false;
        }

        mc.execute(() -> {
            try {
                VnState.clearScene();
                VnState.attachMaid(maid.getPersistentData());
                injectWorldState(player);

                VisualNovelEngine engine = new VisualNovelEngine(
                        script,
                        maid.getType().toShortString(),
                        maid.getUUID().toString(),
                        player.getName().getString(),
                        player.level().isDay(),
                        new PlayerInventoryHandler(player)
                );
                engine.currentSceneId = sceneId;

                DialogueScreen screen = new DialogueScreen(engine, maid, player);
                mc.setScreen(screen);
            } catch (Exception e) {
                VnMod.error("Failed to open scene " + sceneId, e);
            } finally {
                // The screen holds the engine; detach only the NBT pointer so a
                // later scene reattaches to whatever entity it belongs to.
                VnState.detachMaid();
            }
        });
        return true;
    }

    /**
     * Push Minecraft world state into the scene scope. Called before every
     * scene so a scene written for the browser (where you pass
     * {@code globals: {isDay: true}}) behaves the same in game.
     */
    private static void injectWorldState(@Nullable Player player) {
        if (player == null) {
            return;
        }
        boolean day = player.level().isDay();
        VnState.set(VnScope.SCENE, VAR_IS_DAY, day);
        VnState.set(VnScope.SCENE, VAR_IS_NIGHT, !day);
        VnState.set(VnScope.SCENE, "playerName", player.getName().getString());
        VnState.set(VnScope.SCENE, "dimension", player.level().dimension().location().toString());

        // What the player is holding, so the spine can react to a gift without
        // the engine needing an item registry. A script branches on
        // @scene.heldItem like it branches on isDay.
        net.minecraft.world.item.ItemStack main = player.getMainHandItem();
        String held = main.isEmpty() ? "minecraft:air"
                : net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getKey(main.getItem()).toString();
        VnState.set(VnScope.SCENE, VAR_HELD_ITEM, held);

        injectPlayerFacts(player);
    }

    /**
     * Snapshot the player and world into the read-only PLAYER scope.
     * <p>
     * Everything here is a cheap fact that a story might reasonably branch on.
     * Expensive lookups (advancements, inventory scans) are resolved lazily on
     * read instead of being dumped in wholesale.
     */
    private static void injectPlayerFacts(Player player) {
        VnState.beginPlayerSnapshot();

        VnState.putPlayer("health", player.getHealth());
        VnState.putPlayer("max_health", player.getMaxHealth());
        VnState.putPlayer("absorption", player.getAbsorptionAmount());
        VnState.putPlayer("food", player.getFoodData().getFoodLevel());
        VnState.putPlayer("saturation", player.getFoodData().getSaturationLevel());
        VnState.putPlayer("xp_level", player.experienceLevel);
        VnState.putPlayer("air", player.getAirSupply());
        VnState.putPlayer("is_sleeping", player.isSleeping());
        VnState.putPlayer("is_sprinting", player.isSprinting());
        VnState.putPlayer("is_creative", player.isCreative());
        VnState.putPlayer("is_spectator", player.isSpectator());
        VnState.putPlayer("dimension", player.level().dimension().location().toString());
        VnState.putPlayer("pos_x", Math.floor(player.getX()));
        VnState.putPlayer("pos_y", Math.floor(player.getY()));
        VnState.putPlayer("pos_z", Math.floor(player.getZ()));

        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            String mode = switch (serverPlayer.gameMode.getGameModeForPlayer()) {
                case CREATIVE -> "creative";
                case ADVENTURE -> "adventure";
                case SPECTATOR -> "spectator";
                default -> "survival";
            };
            VnState.putPlayer("gamemode", mode);
            VnState.putPlayer("difficulty",
                    serverPlayer.level().getDifficulty().getKey().toLowerCase());
            VnState.putPlayer("is_hardcore",
                    serverPlayer.level().getLevelData().isHardcore());
        } else {
            // Single-player client mirror; enough for scene branching.
            VnState.putPlayer("gamemode", player.isCreative() ? "creative" : "survival");
        }

        VnState.putPlayer("time_of_day", player.level().getDayTime() % 24000L);
        VnState.putPlayer("day", player.level().getDayTime() / 24000L);
        VnState.putPlayer("is_raining", player.level().isRaining());
        VnState.putPlayer("is_thundering", player.level().isThundering());
        VnState.putPlayer("moon_phase", player.level().getMoonPhase());
    }

    /**
     * Open a maid's default scene.
     * <p>
     * Order matters and matches both pack eras:
     * <ol>
     *     <li>legacy flat trigger — {@code <characterId>.json}, i.e. the file
     *         name IS the trigger ({@code curipa.json} -> maid "curipa")</li>
     *     <li>modern spine — {@code <characterId>/main.json}</li>
     *     <li>any single scene under the character folder</li>
     * </ol>
     * Legacy first because a flat file is a complete story for that character;
     * only if it is absent do we look for a foldered spine.
     */
    public static boolean openDefault(EntityMaid maid, String characterId) {
        // One file per character; the trigger is the file name. Nothing else to
        // try — the spine inside that file decides what happens today.
        if (!SceneRegistry.exists(characterId)) {
            VnMod.warn("No scene for character '" + characterId
                    + "' (expected assets/" + SceneRegistry.NAMESPACE + "/" + characterId + ".json)");
            return false;
        }
        return open(maid, characterId);
    }
}
