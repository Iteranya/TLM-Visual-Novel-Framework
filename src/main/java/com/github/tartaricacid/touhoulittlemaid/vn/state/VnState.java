package com.github.tartaricacid.touhoulittlemaid.vn.state;

import com.github.tartaricacid.touhoulittlemaid.vn.VnMod;
import com.github.tartaricacid.touhoulittlemaid.vn.state.VnScope.Resolved;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single source of truth for VN variables.
 * <p>
 * Replaces the old MobTalker {@code SaveHandler} + {@code VariableHandler} pair,
 * which appended a full snapshot of the variable map to a list on every save
 * (unbounded growth, last-entry-wins on load, keyed by entity display name).
 * This implementation keeps one map per scope, overwrites on save, and is keyed
 * properly.
 * <ul>
 *     <li>{@link VnScope#WORLD} - one JSON file per profile, stored in the
 *         config directory so starting a new world does not wipe the
 *         relationship.</li>
 *     <li>{@link VnScope#MAID} - written into the entity's NBT, so it travels
 *         with the maid.</li>
 *     <li>{@link VnScope#SCENE} - plain in-memory map, thrown away with the
 *         engine.</li>
 * </ul>
 */
public final class VnState {

    /**
     * NBT key holding this engine's per-maid data.
     */
    public static final String NBT_KEY = "TlmVnState";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    /**
     * Active profile. Swap to keep parallel playthroughs separate.
     */
    private static String profile = "default";

    private static final Map<String, Object> WORLD = new ConcurrentHashMap<>();
    private static final Map<String, Object> SCENE = new ConcurrentHashMap<>();
    /**
     * Read-only facts about the player and world. Cleared and re-injected on
     * every scene open; never written by scripts.
     */
    private static final Map<String, Object> PLAYER = new ConcurrentHashMap<>();
    private static volatile boolean worldDirty = false;

    private VnState() {
    }

    // ---------------------------------------------------------------- profile

    public static String getProfile() {
        return profile;
    }

    public static void setProfile(String newProfile) {
        if (newProfile == null || newProfile.isBlank() || newProfile.equals(profile)) {
            return;
        }
        flush();
        profile = newProfile;
        loadWorld();
    }

    // ------------------------------------------------------------------ paths

    private static Path stateDir() {
        return FMLPaths.CONFIGDIR.get().resolve(VnMod.VN_NAMESPACE).resolve("state");
    }

    private static Path worldFile() {
        return stateDir().resolve(profile + ".json");
    }

    // ------------------------------------------------------------- world load

    public static void loadWorld() {
        Path file = worldFile();
        WORLD.clear();
        if (!Files.exists(file)) {
            worldDirty = true;
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> loaded = GSON.fromJson(reader, MAP_TYPE);
            if (loaded != null) {
                WORLD.putAll(loaded);
            }
            VnMod.log("Loaded world state: " + WORLD.size() + " vars from " + file.getFileName());
        } catch (IOException | RuntimeException e) {
            VnMod.error("Failed to read world state " + file, e);
        }
        worldDirty = false;
    }

    public static void flush() {
        if (!worldDirty) {
            return;
        }
        Path file = worldFile();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(WORLD, MAP_TYPE, writer);
            }
            worldDirty = false;
        } catch (IOException e) {
            VnMod.error("Failed to write world state " + file, e);
        }
    }

    /**
     * Wipe the active profile's world state. Debug / command use only.
     */
    public static void clearWorld() {
        WORLD.clear();
        worldDirty = true;
        flush();
    }

    // ------------------------------------------------------------------- maid

    @Nullable
    private static CompoundTag maidRoot = null;

    /**
     * Point the state store at a live maid's NBT. Call before running a scene,
     * clear afterwards with {@link #detachMaid()}.
     */
    public static void attachMaid(@Nullable CompoundTag tag) {
        maidRoot = tag;
        if (tag != null && !tag.contains(NBT_KEY)) {
            tag.put(NBT_KEY, new CompoundTag());
        }
    }

    public static void detachMaid() {
        maidRoot = null;
    }

    @Nullable
    private static CompoundTag maidData() {
        if (maidRoot == null) {
            return null;
        }
        if (!maidRoot.contains(NBT_KEY)) {
            maidRoot.put(NBT_KEY, new CompoundTag());
        }
        return maidRoot.getCompound(NBT_KEY);
    }

    /** NBT key holding scene-scoped ("local") variables inside the maid tag. */
    private static final String LOCAL_KEY = "local";

    /**
     * The sub-compound holding bare-name variables.
     *
     * These outlive the scene, matching the 2024 engine's per-entity
     * {@code localVariables}. Kept in a sub-compound rather than mixed with
     * {@code @maid.} values so an explicit scope and a bare name can never
     * collide on the same key.
     */
    private static CompoundTag localTag(CompoundTag data) {
        if (!data.contains(LOCAL_KEY)) {
            data.put(LOCAL_KEY, new CompoundTag());
        }
        return data.getCompound(LOCAL_KEY);
    }

    public static boolean hasMaid() {
        return maidRoot != null;
    }

    // ---------------------------------------------------------------- accessors

    public static Object get(VnScope scope, String name, Object def) {
        switch (scope) {
            case WORLD -> {
                Object v = WORLD.get(name);
                return v != null ? v : def;
            }
            case MAID -> {
                CompoundTag data = maidData();
                return data == null ? def : readTag(data, name, def);
            }
            case SCENE -> {
                // Scene-scoped values persist with the maid, matching the 2024
                // engine, where localVariables were written to disk per entity
                // by saveProgress(). A bare name in a script is the common case,
                // so making it evaporate between conversations quietly broke
                // every legacy script that relied on locals surviving.
                CompoundTag data = maidData();
                if (data == null) {
                    // No maid attached (preview, tests): fall back to memory.
                    Object v = SCENE.get(name);
                    return v != null ? v : def;
                }
                CompoundTag local = localTag(data);
                return readTag(local, name, def);
            }
            case PLAYER -> {
                Object v = PLAYER.get(name);
                if (v != null) {
                    return v;
                }
                // Not in the snapshot: try the lazy facts (advancements,
                // inventory scans). Computed on read, never cached.
                Object lazy = VnPlayerFacts.resolve(name);
                return lazy != null ? lazy : def;
            }
            default -> {
                return def;
            }
        }
    }

    public static Object get(String rawName, Object def) {
        Resolved r = VnScope.resolve(rawName);
        return get(r.scope(), r.name(), def);
    }

    public static void set(VnScope scope, String name, Object value) {
        switch (scope) {
            case WORLD -> {
                if (value == null) {
                    WORLD.remove(name);
                } else {
                    WORLD.put(name, value);
                }
                worldDirty = true;
            }
            case MAID -> {
                CompoundTag data = maidData();
                if (data != null) {
                    writeTag(data, name, value);
                }
            }
            case SCENE -> {
                CompoundTag data = maidData();
                if (data == null) {
                    if (value == null) {
                        SCENE.remove(name);
                    } else {
                        SCENE.put(name, value);
                    }
                } else {
                    writeTag(localTag(data), name, value);
                }
            }
            case PLAYER -> VnMod.warn(
                    "PLAYER." + name + " is read-only; ignoring write. "
                            + "Use a command action to change the player (effect, give, and so on).");
            default -> {
            }
        }
    }

    public static void set(String rawName, Object value) {
        Resolved r = VnScope.resolve(rawName);
        set(r.scope(), r.name(), value);
    }

    public static int getInt(VnScope scope, String name, int def) {
        Object v = get(scope, name, null);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof String s) {
            try {
                return (int) Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static double getDouble(VnScope scope, String name, double def) {
        Object v = get(scope, name, null);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static boolean getBool(VnScope scope, String name, boolean def) {
        Object v = get(scope, name, null);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return def;
    }

    public static String getString(VnScope scope, String name, String def) {
        Object v = get(scope, name, null);
        return v == null ? def : String.valueOf(v);
    }

    public static void addInt(VnScope scope, String name, int delta) {
        set(scope, name, getInt(scope, name, 0) + delta);
    }

    public static Map<String, Object> worldSnapshot() {
        return new LinkedHashMap<>(WORLD);
    }

    public static Map<String, Object> sceneMap() {
        return SCENE;
    }

    public static void clearScene() {
        SCENE.clear();
    }

    // ------------------------------------------------------------ checkpoint
    //
    // `next` writes a label here and the scene resumes from it next time, which
    // is what the 2024 engine did with localVariables' "checkpoint" key read
    // back in loadProgress(). The fold originally stored the label and then
    // read it nowhere, so `next` looked like a save point and behaved like a
    // comment.

    private static final String CHECKPOINT_KEY = "checkpoint";

    /** Record the resume label. Pass null or blank to clear it. */
    public static void setCheckpoint(@Nullable String label) {
        CompoundTag data = maidData();
        if (data == null) {
            if (label == null || label.isBlank()) {
                SCENE.remove(CHECKPOINT_KEY);
            } else {
                SCENE.put(CHECKPOINT_KEY, label);
            }
            return;
        }
        CompoundTag local = localTag(data);
        if (label == null || label.isBlank()) {
            local.remove(CHECKPOINT_KEY);
        } else {
            local.putString(CHECKPOINT_KEY, label);
        }
    }

    /** The label the story should resume at, or null when there is none. */
    @Nullable
    public static String getCheckpoint() {
        CompoundTag data = maidData();
        if (data == null) {
            Object v = SCENE.get(CHECKPOINT_KEY);
            return v instanceof String s && !s.isBlank() ? s : null;
        }
        CompoundTag local = localTag(data);
        if (!local.contains(CHECKPOINT_KEY)) {
            return null;
        }
        String label = local.getString(CHECKPOINT_KEY);
        return label.isBlank() ? null : label;
    }

    /** Drop the resume point, e.g. once the story has run past it. */
    public static void clearCheckpoint() {
        setCheckpoint(null);
    }

    /**
     * Replace the read-only player snapshot. Called by the launcher before every
     * scene so a script always sees the current moment.
     */
    public static void beginPlayerSnapshot() {
        PLAYER.clear();
    }

    /**
     * Record one player fact. Values are simple scalars so scripts can compare
     * them like any other variable.
     */
    public static void putPlayer(String name, Object value) {
        if (name == null || value == null) {
            return;
        }
        PLAYER.put(name, value);
    }

    public static Map<String, Object> playerSnapshot() {
        return new LinkedHashMap<>(PLAYER);
    }

    // ------------------------------------------------------------------- NBT IO

    private static Object readTag(CompoundTag data, String name, Object def) {
        if (!data.contains(name)) {
            return def;
        }
        Tag t = data.get(name);
        if (t == null) {
            return def;
        }
        switch (t.getId()) {
            case Tag.TAG_BYTE:
                return data.getBoolean(name);
            case Tag.TAG_INT:
                return data.getInt(name);
            case Tag.TAG_LONG:
                return data.getLong(name);
            case Tag.TAG_FLOAT:
            case Tag.TAG_DOUBLE:
                return data.getDouble(name);
            default:
                return data.getString(name);
        }
    }

    private static void writeTag(CompoundTag data, String name, Object value) {
        if (value == null) {
            data.remove(name);
        } else if (value instanceof Boolean b) {
            data.putBoolean(name, b);
        } else if (value instanceof Integer i) {
            data.putInt(name, i);
        } else if (value instanceof Long l) {
            data.putLong(name, l);
        } else if (value instanceof Number n) {
            data.putDouble(name, n.doubleValue());
        } else {
            data.putString(name, String.valueOf(value));
        }
    }
}
