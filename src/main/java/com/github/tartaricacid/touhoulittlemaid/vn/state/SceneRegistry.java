package com.github.tartaricacid.touhoulittlemaid.vn.state;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.vn.VnMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Scene discovery and loading.
 * <p>
 * Scenes are <strong>resource-pack content</strong>, exactly like the original
 * Hikarin framework: a content pack is a plain zip the player drops into
 * {@code resourcepacks/}, and scene JSON lives at
 * <pre>
 *   assets/tlm_vn/scenes/&lt;character&gt;/&lt;scene&gt;.json
 * </pre>
 * A dedicated namespace means content packs never have to pretend to be
 * {@code touhou_little_maid}, and a pack can never collide with TLM's own
 * assets.
 * <p>
 * A config fallback is kept for authoring (hot-reload without rebuilding a
 * pack): {@code config/tlm_vn/scenes/...}. Resource packs always win, so an
 * installed pack cannot be silently overridden by a stale dev folder.
 */
public final class SceneRegistry {

    /**
     * Asset namespace shared by the framework and every content pack.
     */
    public static final String NAMESPACE = "tlm_vn";

    /**
     * Path prefix inside a resource pack, without {@code assets/<ns>/}.
     */
    public static final String SCENE_DIR = "scenes";

    /**
     * Legacy MobTalker/Hikarin namespace. Old packs predate the fold and use
     * {@code assets/mobtalkerredux/<trigger>.json} — one flat file per
     * character, where the trigger is the file name. Supported so existing
     * content keeps working without conversion.
     */
    public static final String LEGACY_NAMESPACE = "mobtalkerredux";

    /**
     * Item-scene subfolder inside a character's folder. Item scenes are matched
     * by their {@code requires.item} field, not by filename; the folder exists
     * purely so a character with dozens of toys stays navigable.
     */
    public static final String ITEM_DIR = "items";

    private static final Gson GSON = new GsonBuilder()
            .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE)
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final Type LIST_TYPE = new TypeToken<List<Map<String, Object>>>() {
    }.getType();

    public enum Source {
        RESOURCE_PACK,
        CONFIG
    }

    public record SceneEntry(String id, Source source, String detail) {
    }

    private SceneRegistry() {
    }

    // ------------------------------------------------------------------ config

    public static Path configScenesRoot() {
        return FMLPaths.CONFIGDIR.get().resolve(VnMod.VN_NAMESPACE).resolve(SCENE_DIR);
    }

    // ------------------------------------------------------------------ listing

    public static Set<String> listIds() {
        return new TreeSet<>(listEntries().keySet());
    }

    public static Map<String, SceneEntry> listEntries() {
        Map<String, SceneEntry> out = new LinkedHashMap<>();

        // Config first, so packs can overwrite it.
        for (String id : scanConfig()) {
            out.put(id, new SceneEntry(id, Source.CONFIG, configScenesRoot().toString()));
        }
        for (Map.Entry<String, SceneEntry> e : scanResourcePacks().entrySet()) {
            out.put(e.getKey(), e.getValue());
        }

        // Legacy flat triggers: only ids we have been told about (maid names),
        // and only if the file genuinely resolves in the legacy namespace.
        for (String id : KNOWN_LEGACY_IDS) {
            if (!out.containsKey(id) && legacyExists(id)) {
                out.put(id, new SceneEntry(id, Source.RESOURCE_PACK, "legacy"));
            }
        }

        return out;
    }

    public static boolean exists(String sceneId) {
        return locate(sceneId) != null;
    }

    // ------------------------------------------------------------------ loading

    public static List<Map<String, Object>> load(String sceneId) {
        Located located = locate(sceneId);
        if (located == null) {
            VnMod.warn("Scene not found: " + sceneId);
            return new ArrayList<>();
        }
        if (located.resource() != null) {
            return loadFromResource(located.resource(), sceneId);
        }
        return loadFromPath(located.path(), sceneId);
    }

    /**
     * Cheap peek at the first {@code meta} action, for a scene's
     * {@code requires} block.
     */
    @Nullable
    public static Map<String, Object> peekMeta(String sceneId) {
        for (Map<String, Object> action : load(sceneId)) {
            if ("meta".equals(action.get("type"))) {
                return action;
            }
        }
        return null;
    }

    private static List<Map<String, Object>> loadFromResource(ResourceLocation rl, String sceneId) {
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(rl);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            List<Map<String, Object>> actions = GSON.fromJson(reader, LIST_TYPE);
            if (actions == null) {
                VnMod.warn("Scene is empty: " + sceneId);
                return new ArrayList<>();
            }
            VnMod.log("Loaded scene " + sceneId + " from pack (" + actions.size() + " actions)");
            return actions;
        } catch (IOException | JsonParseException e) {
            VnMod.error("Failed to load scene " + sceneId + " from " + rl, e);
            return new ArrayList<>();
        }
    }

    private static List<Map<String, Object>> loadFromPath(Path file, String sceneId) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<Map<String, Object>> actions = GSON.fromJson(reader, LIST_TYPE);
            if (actions == null) {
                VnMod.warn("Scene is empty: " + sceneId);
                return new ArrayList<>();
            }
            VnMod.log("Loaded scene " + sceneId + " from config (" + actions.size() + " actions)");
            return actions;
        } catch (IOException | JsonParseException e) {
            VnMod.error("Failed to load scene " + sceneId + " from " + file, e);
            return new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------ locating

    private record Located(@Nullable ResourceLocation resource, @Nullable Path path) {
    }

    @Nullable
    private static Located locate(String sceneId) {
        if (sceneId == null || sceneId.isBlank()) {
            return null;
        }
        String cleaned = sceneId.replace('\\', '/').trim();
        if (cleaned.contains("..")) {
            VnMod.warn("Rejected scene id with path traversal: " + sceneId);
            return null;
        }
        if (cleaned.toLowerCase().endsWith(".json")) {
            cleaned = cleaned.substring(0, cleaned.length() - 5);
        }

        // 1. Resource packs. The jar counts as one, so bundled demo scenes
        //    resolve here with zero setup.
        ResourceLocation rl = new ResourceLocation(NAMESPACE, SCENE_DIR + "/" + cleaned + ".json");
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        if (manager != null) {
            Optional<Resource> found = manager.getResource(rl);
            if (found.isPresent()) {
                return new Located(rl, null);
            }
        }

        // 2. Legacy flat scenes: assets/mobtalkerredux/<trigger>.json
        //    The trigger is the file name, so "curipa.json" is scene id "curipa".
        //    Resolved via getResource (namespace + full path), which is the only
        //    reliable API here — listResources cannot enumerate a namespace.
        if (manager != null) {
            String fileName = cleaned.replace('/', '_');
            ResourceLocation legacy = new ResourceLocation(LEGACY_NAMESPACE, fileName + ".json");
            if (manager.getResource(legacy).isPresent()) {
                return new Located(legacy, null);
            }
        }

        // 3. Config fallback for authoring.
        Path root = configScenesRoot().normalize();
        Path resolved = root.resolve(cleaned + ".json").normalize();
        if (!resolved.startsWith(root)) {
            VnMod.warn("Rejected scene id outside config scenes root: " + sceneId);
            return null;
        }
        return Files.isRegularFile(resolved) ? new Located(null, resolved) : null;
    }

    // ------------------------------------------------------------------ scanning

    private static Set<String> scanConfig() {
        Set<String> ids = new TreeSet<>();
        Path root = configScenesRoot();
        if (!Files.isDirectory(root)) {
            return ids;
        }
        try (var walk = Files.walk(root, 4)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                    .forEach(p -> ids.add(toId(root.relativize(p).toString())));
        } catch (IOException e) {
            VnMod.error("Failed to scan config scenes in " + root, e);
        }
        return ids;
    }

    private static Map<String, SceneEntry> scanResourcePacks() {
        Map<String, SceneEntry> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return out;
        }
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        if (manager == null) {
            return out;
        }

        // Legacy flat scenes first, so a modern pack can shadow them.
        scanLegacy(manager, out);
        // Minecraft's MultiPackResourceManager REJECTS a trailing slash with
        // "IllegalArgumentException: Trailing slash in path". Passing SCENE_DIR
        // + "/" threw on every call, which is why /tlmvn list never worked.
        // The prefix must be bare: "scenes".
        final String prefix = SCENE_DIR;
        final String prefixWithSlash = prefix + "/";

        Map<ResourceLocation, Resource> matches;
        try {
            matches = manager.listResources(
                    prefix, rl -> NAMESPACE.equals(rl.getNamespace())
                            && rl.getPath().toLowerCase().endsWith(".json"));
        } catch (RuntimeException e) {
            // One malformed pack must not take down scene listing for every
            // other pack. Log and fall back to whatever config offered.
            VnMod.error("Resource pack scene scan failed; only config scenes will be listed", e);
            return out;
        }

        for (Map.Entry<ResourceLocation, Resource> e : matches.entrySet()) {
            String path = e.getKey().getPath();
            if (!path.startsWith(prefixWithSlash)) {
                continue;
            }
            String id = toId(path.substring(prefixWithSlash.length()));
            if (id.isBlank()) {
                continue;
            }
            String pack;
            try {
                pack = e.getValue().sourcePackId();
            } catch (RuntimeException ex) {
                pack = "unknown";
            }
            out.put(id, new SceneEntry(id, Source.RESOURCE_PACK, pack));
        }
        return out;
    }

    /**
     * Scan the legacy {@code assets/mobtalkerredux/} namespace.
     * <p>
     * Old packs store one flat file per character and the trigger is the file
     * name ({@code curipa.json} -> maid named "curipa"). Everything at the top
     * of that namespace ending in {@code .json} is treated as a scene; the
     * {@code sounds.json} style entries simply fail to parse as an action list
     * later and are skipped at load time.
     */
    /**
     * Legacy flat scenes are discovered by <strong>probing</strong>, not scanning.
     * <p>
     * {@code ResourceManager.listResources(prefix, filter)} is prefix-scoped and
     * namespace-agnostic: an empty prefix only walks pack-root depth, and
     * {@code ResourceLocation} in that API carries no namespace to filter on. So
     * there is no reliable way to enumerate "everything in namespace X".
     * <p>
     * Instead we ask the maid-facing question directly. A legacy scene's id is
     * the trigger, which equals the maid's name, so we only ever need to resolve
     * ids the caller already has: {@link #exists(String)} and
     * {@link #locate(String)} handle those via {@code getResource}, which does
     * take a full namespace + path.
     * <p>
     * This method therefore exists only to register a small set of known
     * characters discovered from the world's maids — see
     * {@link #registerLegacyTriggers(java.util.Collection)}.
     */
    private static void scanLegacy(ResourceManager manager, Map<String, SceneEntry> out) {
        // Intentionally empty: see registerLegacyTriggers.
    }

    private static final Set<String> KNOWN_LEGACY_IDS = new java.util.LinkedHashSet<>();

    /**
     * Register candidate legacy trigger ids (typically maid names) so they show
     * up in listings. Resolution itself is done per-id by {@link #locate(String)},
     * so an id here that has no file simply resolves to nothing.
     */
    public static void registerLegacyTriggers(java.util.Collection<String> ids) {
        for (String raw : ids) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String id = raw.toLowerCase().replace(' ', '_');
            KNOWN_LEGACY_IDS.add(id);
        }
    }

    /**
     * @return legacy trigger id that exists as {@code assets/mobtalkerredux/<id>.json}
     */
    private static boolean legacyExists(String id) {
        ResourceManager manager = managerOrNull();
        if (manager == null) {
            return false;
        }
        try {
            return manager.getResource(
                    new ResourceLocation(LEGACY_NAMESPACE, id + ".json")).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ResourceManager managerOrNull() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : mc.getResourceManager();
    }

    private static String toId(String raw) {
        String s = raw.replace('\\', '/');
        if (s.toLowerCase().endsWith(".json")) {
            s = s.substring(0, s.length() - 5);
        }
        return s;
    }

    /**
     * Human-readable report for {@code /tlmvn list}.
     */
    public static List<String> describe() {
        Map<String, SceneEntry> entries = listEntries();
        if (entries.isEmpty()) {
            return List.of("no scenes installed. Expected assets/" + NAMESPACE + "/"
                    + SCENE_DIR + "/<character>/<scene>.json inside a resource pack.");
        }
        List<String> lines = new ArrayList<>();
        for (SceneEntry e : entries.values()) {
            String where = e.source() == Source.RESOURCE_PACK ? "pack:" + e.detail() : "config";
            lines.add("  " + e.id() + "  (" + where + ")");
        }
        return lines;
    }

    /**
     * Scenes this maid has unlocked right now.
     * <p>
     * A scene's first {@code meta} action may carry a {@code requires} block:
     * <pre>
     * { "type":"meta", "action":"start",
     *   "requires": { "affection": 384,
     *                 "flags": ["cupa.gift_stage:3"],
     *                 "seen": ["cupa/night"] } }
     * </pre>
     * {@code affection} compared against {@code @world.affection.<character>},
     * {@code flags} must all be truthy in maid or world scope, {@code seen}
     * entries must be true in {@code @maid.seen.<id>}.
     * <p>
     * Scenes without {@code requires} are always available. This is what
     * {@code play_scene} uses to build its enum, so the model only ever sees
     * what she would actually agree to.
     */
    public static Set<String> listUnlockedFor(EntityMaid maid) {
        String characterId = sceneFolderOf(maid);
        Set<String> out = new TreeSet<>();
        for (String id : listForCharacter(characterId)) {
            if (isUnlocked(maid, characterId, id)) {
                out.add(id);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Whether a specific scene is currently unlocked for this maid.
     */
    public static boolean isUnlocked(EntityMaid maid, String characterId, String sceneId) {
        Map<String, Object> meta = peekMeta(sceneId);
        if (meta == null) {
            return false;
        }
        Object requiresObj = meta.get("requires");
        if (!(requiresObj instanceof Map<?, ?> requires) || requires.isEmpty()) {
            return true;
        }

        // affection
        Object affObj = requires.get("affection");
        if (affObj instanceof Number needed) {
            int affection = VnState.getInt(VnScope.WORLD, "affection." + characterId, 0);
            if (affection < needed.intValue()) {
                return false;
            }
        }

        // flags: every entry must be truthy. "name:value" compares as string.
        Object flagsObj = requires.get("flags");
        if (flagsObj instanceof List<?> flags) {
            for (Object flag : flags) {
                String spec = String.valueOf(flag);
                int colon = spec.lastIndexOf(':');
                if (colon > 0) {
                    String key = spec.substring(0, colon);
                    String expected = spec.substring(colon + 1);
                    String actual = VnState.getString(VnScope.MAID, key,
                            VnState.getString(VnScope.WORLD, key, ""));
                    if (!expected.equals(actual)) {
                        return false;
                    }
                } else if (!VnState.getBool(VnScope.MAID, spec,
                        VnState.getBool(VnScope.WORLD, spec, false))) {
                    return false;
                }
            }
        }

        // seen: prerequisite scene ids recorded under @maid.seen.<id>
        Object seenObj = requires.get("seen");
        if (seenObj instanceof List<?> seen) {
            for (Object req : seen) {
                String prefix2 = String.valueOf(req).replace('/', '.');
                if (!VnState.getBool(VnScope.MAID, "seen." + prefix2, false)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Scene folder for a maid: her custom name if she has one, else her model
     * id. Lowercased, spaces to underscores. Live in the registry rather than
     * the item so the state layer never has to import a client item class.
     */
    public static String sceneFolderOf(EntityMaid maid) {
        if (maid == null) {
            return "maid";
        }
        String raw = "";
        if (maid.getCustomName() != null) {
            raw = maid.getCustomName().getString();
        }
        if (raw.isBlank()) {
            try {
                raw = maid.getModelId();
            } catch (Exception ignored) {
                raw = null;
            }
        }
        if (raw == null || raw.isBlank()) {
            raw = "maid";
        }
        return raw.toLowerCase().replace(' ', '_');
    }

    /**
     * Find the best item scene for a held item.
     * <p>
     * Looks only in {@code <character>/items/} — the spine and story scenes are
     * never considered. Among matches, picks the one with the highest
     * {@code requires.affection} that the maid currently passes, so you always
     * see the most advanced reaction she would accept.
     * <p>
     * Deliberately <strong>uncached</strong>. A character folder holds tens of
     * small files, not thousands; a scan costs a couple of milliseconds and
     * removing the cache removes a whole class of stale-data bugs.
     *
     * @return matching scene id, or null when nothing matches
     */
    @Nullable
    public static String findItemScene(EntityMaid maid, String characterId, net.minecraft.world.item.Item item) {
        if (item == null) {
            return null;
        }
        String itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item).toString();
        String itemPrefix = characterId + "/" + ITEM_DIR + "/";

        Set<String> candidates = new TreeSet<>();
        for (String id : listIds()) {
            if (!id.startsWith(itemPrefix)) {
                continue;
            }
            Map<String, Object> meta = peekMeta(id);
            if (meta == null) {
                continue;
            }
            Object requiresObj = meta.get("requires");
            if (!(requiresObj instanceof Map<?, ?> requires)) {
                continue;
            }
            Object declared = requires.get("item");
            if (declared == null || !itemId.equals(String.valueOf(declared))) {
                continue;
            }
            if (isUnlocked(maid, characterId, id)) {
                candidates.add(id);
            }
        }

        if (candidates.isEmpty()) {
            VnMod.log("Held item " + itemId + " -> no matching scene in " + characterId
                    + "/" + ITEM_DIR + "/, using spine");
            return null;
        }

        // Highest affection requirement wins = most advanced accepted reaction.
        String best = null;
        int bestThreshold = Integer.MIN_VALUE;
        for (String id : candidates) {
            int threshold = requiredAffection(id);
            VnMod.log("  candidate " + id + " requires affection " + threshold + " -> MATCH");
            if (threshold > bestThreshold) {
                bestThreshold = threshold;
                best = id;
            }
        }
        VnMod.log("Held item " + itemId + " -> " + best);
        return best;
    }

    /**
     * Affection threshold declared by a scene's {@code requires} block, or
     * {@link Integer#MIN_VALUE} when it declares none.
     */
    public static int requiredAffection(String sceneId) {
        Map<String, Object> meta = peekMeta(sceneId);
        if (meta == null) {
            return Integer.MIN_VALUE;
        }
        Object requiresObj = meta.get("requires");
        if (!(requiresObj instanceof Map<?, ?> requires)) {
            return Integer.MIN_VALUE;
        }
        Object aff = requires.get("affection");
        return aff instanceof Number n ? n.intValue() : Integer.MIN_VALUE;
    }

    /**
     * Scenes belonging to one character folder, e.g. {@code cupa/}.
     */
    public static Set<String> listForCharacter(String characterId) {
        Set<String> out = new TreeSet<>();
        String prefix = characterId + "/";
        for (String id : listIds()) {
            if (id.startsWith(prefix)) {
                out.add(id);
            }
        }
        return Collections.unmodifiableSet(out);
    }
}
