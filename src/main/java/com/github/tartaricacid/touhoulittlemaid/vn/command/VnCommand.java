package com.github.tartaricacid.touhoulittlemaid.vn.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.vn.VnMod;
import com.github.tartaricacid.touhoulittlemaid.vn.item.VnTalkItem;
import com.github.tartaricacid.touhoulittlemaid.vn.state.SceneLauncher;
import com.github.tartaricacid.touhoulittlemaid.vn.state.SceneRegistry;
import com.github.tartaricacid.touhoulittlemaid.vn.state.VnScope;
import com.github.tartaricacid.touhoulittlemaid.vn.state.VnState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;

/**
 * {@code /tlmvn ...} — debug and authoring tools.
 * <p>
 * Exists so you never have to relaunch Minecraft to check whether a scene file
 * parses, whether a variable stuck, or whether the registry sees your folder.
 * Single-player only, so no permission gating.
 */
public final class VnCommand {

    private VnCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tlmvn")
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            if (ctx.getSource().getEntity() instanceof net.minecraft.world.entity.player.Player p) {
                                SceneRegistry.registerLegacyTriggers(java.util.List.of(
                                        "curipa", "cupa", "cupa_test", "andr", "andrel"));
                            }
                            var entries = SceneRegistry.listEntries();
                            if (entries.isEmpty()) {
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "§e[TLM-VN] No scenes installed."), false);
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "§7  resource pack: assets/tlm_vn/scenes/<character>/<scene>.json"),
                                        false);
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "§7  dev fallback:  " + SceneRegistry.configScenesRoot()), false);
                                return 0;
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§a[TLM-VN] " + entries.size() + " scene(s):"), false);
                            for (String line : SceneRegistry.describe()) {
                                ctx.getSource().sendSuccess(() -> Component.literal("§f" + line), false);
                            }
                            return entries.size();
                        }))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            var ids = SceneRegistry.listIds();
                            VnState.flush();
                            VnState.loadWorld();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§a[TLM-VN] Reloaded. " + ids.size() + " scene(s), profile '"
                                            + VnState.getProfile() + "'."), false);
                            return 1;
                        }))
                .then(Commands.literal("play")
                        .then(Commands.argument("scene", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    CommandSourceStack src = ctx.getSource();
                                    ServerPlayer player = src.getPlayerOrException();
                                    String sceneId = StringArgumentType.getString(ctx, "scene");

                                    EntityMaid maid = nearestMaid(player, 32);
                                    if (maid == null) {
                                        src.sendFailure(Component.literal(
                                                "§c[TLM-VN] No maid within 32 blocks. Stand near her."));
                                        return 0;
                                    }
                                    boolean ok = SceneLauncher.open(maid, sceneId);
                                    src.sendSuccess(() -> Component.literal(ok
                                            ? "§a[TLM-VN] Opening '" + sceneId + "' on " + VnTalkItem.characterIdOf(maid)
                                            : "§c[TLM-VN] Could not open '" + sceneId + "' — see log."), false);
                                    return ok ? 1 : 0;
                                })))
                .then(Commands.literal("talk")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            ServerPlayer player = src.getPlayerOrException();
                            EntityMaid maid = nearestMaid(player, 32);
                            if (maid == null) {
                                src.sendFailure(Component.literal("§c[TLM-VN] No maid within 32 blocks."));
                                return 0;
                            }
                            String id = VnTalkItem.characterIdOf(maid);
                            boolean ok = SceneLauncher.openDefault(maid, id);
                            src.sendSuccess(() -> Component.literal(ok
                                    ? "§a[TLM-VN] Opening default scene for '" + id + "'"
                                    : "§c[TLM-VN] No scenes for '" + id + "'"), false);
                            return ok ? 1 : 0;
                        }))
                .then(Commands.literal("vars")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            var world = VnState.worldSnapshot();
                            src.sendSuccess(() -> Component.literal(
                                    "§6[TLM-VN] world[" + VnState.getProfile() + "] §f" + world), false);
                            ServerPlayer player = src.getPlayerOrException();
                            EntityMaid maid = nearestMaid(player, 32);
                            if (maid != null) {
                                var tag = maid.getPersistentData().getCompound(VnState.NBT_KEY);
                                src.sendSuccess(() -> Component.literal(
                                        "§6[TLM-VN] maid[" + VnTalkItem.characterIdOf(maid) + "] §f" + tag), false);
                            }
                            return 1;
                        }))
                .then(Commands.literal("set")
                        .then(Commands.argument("scope", StringArgumentType.word())
                                .then(Commands.argument("var", StringArgumentType.word())
                                        .then(Commands.argument("value", IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    String scopeName = StringArgumentType.getString(ctx, "scope");
                                                    String var = StringArgumentType.getString(ctx, "var");
                                                    int value = IntegerArgumentType.getInteger(ctx, "value");
                                                    VnScope scope;
                                                    try {
                                                        scope = VnScope.valueOf(scopeName.toUpperCase());
                                                    } catch (IllegalArgumentException e) {
                                                        ctx.getSource().sendFailure(Component.literal(
                                                                "§c[TLM-VN] scope must be world, maid or scene"));
                                                        return 0;
                                                    }
                                                    if (scope == VnScope.MAID) {
                                                        EntityMaid maid = nearestMaid(
                                                                ctx.getSource().getPlayerOrException(), 32);
                                                        if (maid == null) {
                                                            ctx.getSource().sendFailure(Component.literal(
                                                                    "§c[TLM-VN] No maid nearby for maid scope."));
                                                            return 0;
                                                        }
                                                        VnState.attachMaid(maid.getPersistentData());
                                                        VnState.set(scope, var, value);
                                                        VnState.detachMaid();
                                                    } else {
                                                        VnState.set(scope, var, value);
                                                    }
                                                    VnState.flush();
                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            "§a[TLM-VN] " + scope + "." + var + " = " + value), false);
                                                    return 1;
                                                })))))
                .then(Commands.literal("reset")
                        .executes(ctx -> {
                            VnState.clearWorld();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§c[TLM-VN] World state wiped for profile '"
                                            + VnState.getProfile() + "'."), false);
                            return 1;
                        }))
                .then(Commands.literal("profile")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    VnState.setProfile(name);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "§a[TLM-VN] Profile -> " + VnState.getProfile()), false);
                                    return 1;
                                }))));
        VnMod.log("Registered /tlmvn");
    }

    private static EntityMaid nearestMaid(ServerPlayer player, double radius) {
        AABB box = player.getBoundingBox().inflate(radius);
        List<EntityMaid> found = player.level().getEntitiesOfClass(EntityMaid.class, box);
        return found.stream()
                .min(Comparator.comparingDouble(m -> m.distanceToSqr(player)))
                .orElse(null);
    }
}
