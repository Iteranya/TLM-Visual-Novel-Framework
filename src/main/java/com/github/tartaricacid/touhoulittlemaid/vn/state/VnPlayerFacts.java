package com.github.tartaricacid.touhoulittlemaid.vn.state;

import com.github.tartaricacid.touhoulittlemaid.vn.VnMod;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/**
 * Resolves player facts that are too expensive to inject eagerly.
 * <p>
 * Health, food and gamemode are cheap and get snapshot into the PLAYER scope on
 * every scene open. Two families are not:
 * <ul>
 *     <li>{@code @player.advancement.<id>} — there are thousands of
 *         advancements; walking them all would be absurd.</li>
 *     <li>{@code @player.inventory_has.<item>} — 700+ item types.</li>
 * </ul>
 * Those are computed when a script actually reads them, and nothing is cached —
 * same reasoning as the scene registry: a stale cache is worse than a cheap
 * lookup.
 * <p>
 * This is what makes achievements usable as quest gates:
 * <pre>
 * { "type":"conditional", "scope":"PLAYER",
 *   "var":"advancement.minecraft:nether", "condition":"equal", "value":true }
 * </pre>
 */
public final class VnPlayerFacts {

    public static final String PREFIX_ADVANCEMENT = "advancement.";
    public static final String PREFIX_INVENTORY = "inventory_has.";

    private VnPlayerFacts() {
    }

    /**
     * Resolve a PLAYER variable that is not in the snapshot.
     *
     * @return the value, or {@code null} when the name is not a lazy fact
     */
    @Nullable
    public static Object resolve(String name) {
        if (name == null) {
            return null;
        }
        if (name.startsWith(PREFIX_ADVANCEMENT)) {
            String advancement = name.substring(PREFIX_ADVANCEMENT.length());
            return hasAdvancement(advancement);
        }
        if (name.startsWith(PREFIX_INVENTORY)) {
            String itemId = name.substring(PREFIX_INVENTORY.length());
            return inventoryHas(itemId);
        }
        return null;
    }

    /**
     * Whether the player has completed an advancement ("done"), not merely
     * started it. Accepts {@code namespace:path} or a bare path for
     * {@code minecraft}.
     */
    public static boolean hasAdvancement(String advancementId) {
        ResourceLocation id = parseAdvancementId(advancementId);
        if (id == null) {
            return false;
        }

        Player player = playerOrNull();
        if (player == null) {
            return false;
        }

        // Server side has the authoritative progress map.
        if (player instanceof ServerPlayer serverPlayer) {
            MinecraftServer server = serverPlayer.getServer();
            if (server == null) {
                return false;
            }
            Advancement advancement = server.getAdvancements().getAdvancement(id);
            if (advancement == null) {
                VnMod.warn("Unknown advancement: " + id);
                return false;
            }
            PlayerAdvancements progress = serverPlayer.getAdvancements();
            if (progress == null) {
                return false;
            }
            AdvancementProgress ap = progress.getOrStartProgress(advancement);
            return ap.isDone();
        }

        // Client thread (the normal case: scenes open on the render thread).
        // Instead of tracking packets or caching, hop to the integrated server:
        // in single-player the authoritative ServerPlayer for this UUID is one
        // lookup away, with the real AdvancementProgress map.
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            VnMod.warn("Advancement lookup needs the integrated server; not in single-player?");
            return false;
        }
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player.getUUID());
        if (serverPlayer == null) {
            return false;
        }
        Advancement advancement = server.getAdvancements().getAdvancement(id);
        if (advancement == null) {
            VnMod.warn("Unknown advancement: " + id);
            return false;
        }
        PlayerAdvancements progress = serverPlayer.getAdvancements();
        if (progress == null) {
            return false;
        }
        return progress.getOrStartProgress(advancement).isDone();
    }

    /**
     * Whether the player carries at least one of the given item anywhere in their
     * inventory.
     */
    public static boolean inventoryHas(String itemId) {
        Item item = itemOrNull(itemId);
        Player player = playerOrNull();
        if (item == null || player == null) {
            return false;
        }
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return true;
            }
        }
        return false;
    }

    /**
     * How many of an item the player is carrying, across all slots.
     */
    public static int inventoryCount(String itemId) {
        Item item = itemOrNull(itemId);
        Player player = playerOrNull();
        if (item == null || player == null) {
            return 0;
        }
        int total = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ helpers

    @Nullable
    private static ResourceLocation parseAdvancementId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id != null) {
            return id;
        }
        // Bare path -> minecraft namespace.
        return ResourceLocation.tryParse("minecraft:" + raw);
    }

    @Nullable
    private static Item itemOrNull(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        return id == null ? null : ForgeRegistries.ITEMS.getValue(id);
    }

    @Nullable
    private static Player playerOrNull() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : mc.player;
    }
}
