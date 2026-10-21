package com.github.tartaricacid.touhoulittlemaid.vn.state;

import com.github.tartaricacid.touhoulittlemaid.vn.VnMod;

/**
 * Where a VN variable lives.
 * <p>
 * Mirrors the two maps the JavaScript viewer already keeps
 * ({@code this.globals} / {@code this.variables} in {@code engine.js}), so a
 * story behaves identically in the browser preview and in Minecraft.
 * <ul>
 *     <li>{@link #WORLD} — persists across scenes, maid deaths and even new
 *         worlds (profile-level file). This is where affection, quest flags
 *         and recipe unlocks belong.</li>
 *     <li>{@link #MAID} — persists with the entity (NBT). Per-maid story
 *         position, reluctance, seen-scene flags.</li>
 *     <li>{@link #SCENE} — dies with the scene. Temporary working values.</li>
 *     <li>{@link #PLAYER} — read-only facts about the player and world, injected
 *         fresh on every scene open: health, food, gamemode, achievements,
 *         dimension, held item. Never persisted, never writable.</li>
 * </ul>
 * Bare variable names in scripts resolve to {@link #SCENE}, which keeps every
 * existing MobTalker-era script working untouched.
 */
public enum VnScope {
    WORLD("@world."),
    MAID("@maid."),
    SCENE("@scene."),
    PLAYER("@player.");

    private final String prefix;

    VnScope(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    /**
     * Resolve a raw script variable name to its scope.
     * <p>
     * {@code "@world.foo"} -> WORLD + "foo".
     * Anything else -> SCENE (backwards compatible).
     */
    public static Resolved resolve(String rawName) {
        if (rawName == null) {
            return new Resolved(SCENE, "");
        }
        for (VnScope scope : values()) {
            if (rawName.startsWith(scope.prefix)) {
                return new Resolved(scope, rawName.substring(scope.prefix.length()));
            }
        }
        return new Resolved(SCENE, rawName);
    }

    /**
     * Also accept an explicit {@code "scope"} field from the script JSON,
     * falling back to prefix sniffing when absent.
     */
    public static Resolved resolve(String rawName, String explicitScope) {
        if (explicitScope != null && !explicitScope.isBlank()) {
            for (VnScope scope : values()) {
                if (scope.name().equalsIgnoreCase(explicitScope)) {
                    // Strip the prefix too, so "@maid.x" + scope=MAID works.
                    Resolved prefixed = resolve(rawName);
                    return new Resolved(scope, prefixed.name());
                }
            }
            VnMod.warn("Unknown scope '" + explicitScope + "' for variable '" + rawName + "', using SCENE");
        }
        return resolve(rawName);
    }

    /**
     * A scope + the variable's real (unprefixed) name.
     */
    public record Resolved(VnScope scope, String name) {
    }
}
