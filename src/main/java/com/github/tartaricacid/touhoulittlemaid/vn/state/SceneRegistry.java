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
    /**
     * Content namespace. One flat file per character, 2024-style:
     * {@code assets/hikarin/<trigger>.json}. The trigger is the file name.
     */
    public static final String NAMESPACE = "hikarin";

    /**
     * Path prefix inside a resource pack, without {@code assets/<ns>/}.
     */
    /**
     * Legacy MobTalker/Hikarin namespace. Old packs predate the fold and use
     * {@code assets/mobtalkerredux/<trigger>.json} — one flat file per
     * character, where the trigger is the file name. Supported so existing
     * content keeps working without conversion.
     */
    /** Read-only fallback so 2024-era packs keep playing unmodified. */
    public static final String LEGACY_NAMESPACE = "mobtalkerredux";

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

    /**
     * Dev fallback while authoring: {@code config/tlm_vn/<trigger>.json}.
     */
    public static Path configScenesRoot() {
        return FMLPaths.CONFIGDIR.get().resolve(VnMod.VN_NAMESPACE);
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
     * Cheap peek at the first {@code meta} action. Used for diagnostics and for
     * reading a scene's declared metadata without walking the whole action list.
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

        // Flat id: "cupa/main" collapses to "cupa_main" only if a nested path was
        // given; a plain trigger like "curipa" stays as-is.
        String flat = cleaned.replace('/', '_');

        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        if (manager != null) {
            // 1. Current namespace, flat file per character.
            ResourceLocation rl = new ResourceLocation(NAMESPACE, flat + ".json");
            if (manager.getResource(rl).isPresent()) {
                return new Located(rl, null);
            }
            // 2. Legacy namespace, same shape, read-only compatibility.
            ResourceLocation legacy = new ResourceLocation(LEGACY_NAMESPACE, flat + ".json");
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
        try (var walk = Files.walk(root, 1)) {
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

        // One flat file per character, exactly like a 2024 MobTalker pack.
        // ResourceLocation carries no namespace in listResources, so we cannot
        // filter by namespace there — we probe each known name instead, which is
        // the only reliable lookup available.
        scanLegacy(manager, out);
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
            return List.of("no scenes installed. Expected assets/" + NAMESPACE
                    + "/<trigger>.json inside a resource pack, or " + configScenesRoot());
        }
        List<String> lines = new ArrayList<>();
        for (SceneEntry e : entries.values()) {
            String where = e.source() == Source.RESOURCE_PACK ? "pack:" + e.detail() : "config";
            lines.add("  " + e.id() + "  (" + where + ")");
        }
        return lines;
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
