package com.github.tartaricacid.touhoulittlemaid.vn.item;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.vn.VnMod;
import com.github.tartaricacid.touhoulittlemaid.vn.state.SceneLauncher;
import com.github.tartaricacid.touhoulittlemaid.vn.state.SceneRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Right-click a maid with this to open her visual novel.
 * <p>
 * Replaces the old {@code MobTalkerItem}, which required the mob to be renamed
 * to a magic trigger word. Here the target is any {@link EntityMaid}, and the
 * scene folder is derived from her custom name (lowercased, spaces to
 * underscores) so naming a maid "Cupa" is all it takes to bind her to
 * {@code config/tlm_vn/scenes/cupa/}.
 * <p>
 * If she has no scenes, she says so instead of failing silently — the old mod
 * returned {@code InteractionResult.PASS} and printed to stdout, which made
 * "why doesn't it work" impossible to diagnose in game.
 */
public class VnTalkItem extends Item {

    public VnTalkItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public @NotNull InteractionResult interactLivingEntity(@NotNull ItemStack stack,
                                                           @NotNull Player player,
                                                           @NotNull LivingEntity target,
                                                           @NotNull InteractionHand hand) {
        if (!(target instanceof EntityMaid maid)) {
            return InteractionResult.PASS;
        }
        Level level = player.level();
        if (!level.isClientSide()) {
            // Everything the VN does is client-side rendering; the item is a
            // UI trigger, not a game action.
            return InteractionResult.SUCCESS;
        }

        String characterId = characterIdOf(maid);

        // Register the trigger so legacy flat packs (filename == trigger) are
        // discoverable for this maid even though namespaces cannot be enumerated.
        SceneRegistry.registerLegacyTriggers(java.util.List.of(characterId));

        // A scene may be the modern spine (<char>/main), any scene under her
        // folder, or a legacy flat file named after her.
        boolean hasScenes = SceneRegistry.exists(characterId + "/main")
                || SceneRegistry.exists(characterId)
                || SceneRegistry.listIds().stream().anyMatch(id -> id.startsWith(characterId + "/"));

        if (!hasScenes) {
            player.displayClientMessage(Component.translatable(
                    "message.tlm_vn.no_scenes", characterId), false);
            VnMod.warn("Maid '" + characterId + "' has no scenes. Expected assets/tlm_vn/scenes/"
                    + characterId + "/ in a resource pack, or " + SceneRegistry.configScenesRoot());
            return InteractionResult.SUCCESS;
        }

        SceneLauncher.openDefault(maid, characterId);
        return InteractionResult.SUCCESS;
    }

    /**
     * Scene folder for a maid: her custom name if she has one, otherwise her
     * model id. Lowercased with spaces replaced.
     */
    public static String characterIdOf(EntityMaid maid) {
        return SceneRegistry.sceneFolderOf(maid);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack,
                                @Nullable Level level,
                                @NotNull List<Component> tooltip,
                                @NotNull TooltipFlag flag) {
        tooltip.add(Component.translatable("item.tlm_vn.vn_talk_item.desc"));
    }
}
