package com.github.tartaricacid.touhoulittlemaid.vn.state;

import com.github.tartaricacid.touhoulittlemaid.vn.VnMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Texture and sound resolution for VN content, across both pack layouts.
 * <p>
 * Scene JSON references assets by a relative path with no namespace, e.g.
 * {@code characters/cupa/default/happy.png}. Where that path actually lives
 * depends on which era the pack comes from:
 * <pre>
 *   modern (tlm_vn):          touhou_little_maid:textures/vn/characters/cupa/default/happy.png
 *   legacy (mobtalkerredux):  mobtalkerredux:textures/characters/cupa/default/happy.png
 * </pre>
 * The first namespace that actually contains the file wins, so a modern pack can
 * shadow a legacy one and old packs keep working untouched. If nothing resolves,
 * the modern path is returned so Minecraft shows the usual missing-texture
 * placeholder instead of crashing.
 */
public final class VnAssets {

    /**
     * Namespace used by the framework's own bundled assets.
     */
    public static final String ASSET_NAMESPACE = "touhou_little_maid";

    /**
     * Subfolder under {@code textures/} for VN art in the modern layout.
     */
    public static final String TEXTURE_SUBDIR = "vn/";

    /**
     * Legacy spellings of the same paths, tried in order when the modern
     * location is missing.
     */
    private static final String LEGACY_NAMESPACE = "mobtalkerredux";

    private VnAssets() {
    }

    /**
     * Resolve a relative sprite/background path to a real texture location.
     *
     * @param relativePath e.g. {@code characters/cupa/default/happy.png}
     */
    public static ResourceLocation texture(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return placeholder();
        }
        String clean = relativePath.replace('\\', '/');
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        // Allow scripts to be explicit: "namespace:path/file.png".
        if (clean.contains(":")) {
            ResourceLocation explicit = ResourceLocation.tryParse(clean);
            return explicit != null ? explicit : placeholder();
        }

        ResourceManager manager = manager();
        if (manager == null) {
            return modern(clean);
        }

        // 1. Modern: touhou_little_maid:textures/vn/<path>
        ResourceLocation modernLoc = modern(clean);
        if (exists(manager, modernLoc)) {
            return modernLoc;
        }

        // 2. Legacy: mobtalkerredux:textures/<path>
        ResourceLocation legacy = new ResourceLocation(LEGACY_NAMESPACE, "textures/" + clean);
        if (exists(manager, legacy)) {
            return legacy;
        }

        VnMod.warn("Texture not found in any pack: " + clean);
        return modernLoc;
    }

    /**
     * Resolve a sound event or music track name.
     * <p>
     * Modern packs register {@code tlm_vn:sound.<name>}; legacy packs used
     * {@code mobtalkerredux:sound.<name>}.
     */
    public static ResourceLocation sound(String name) {
        if (name == null || name.isBlank()) {
            return new ResourceLocation(ASSET_NAMESPACE, "sound.none");
        }
        if (name.contains(":")) {
            ResourceLocation explicit = ResourceLocation.tryParse(name);
            if (explicit != null) {
                return explicit;
            }
        }
        ResourceManager manager = manager();
        ResourceLocation modern = new ResourceLocation(VnMod.VN_NAMESPACE, "sound." + name);
        ResourceLocation legacy = new ResourceLocation(LEGACY_NAMESPACE, "sound." + name);
        if (manager != null && exists(manager, legacy) && !exists(manager, modern)) {
            return legacy;
        }
        return modern;
    }

    public static ResourceLocation music(String name) {
        if (name == null || name.isBlank()) {
            return new ResourceLocation(ASSET_NAMESPACE, "music.none");
        }
        if (name.contains(":")) {
            ResourceLocation explicit = ResourceLocation.tryParse(name);
            if (explicit != null) {
                return explicit;
            }
        }
        ResourceManager manager = manager();
        ResourceLocation modern = new ResourceLocation(VnMod.VN_NAMESPACE, "music." + name);
        ResourceLocation legacy = new ResourceLocation(LEGACY_NAMESPACE, "music." + name);
        if (manager != null && exists(manager, legacy) && !exists(manager, modern)) {
            return legacy;
        }
        return modern;
    }

    // ------------------------------------------------------------------ helpers

    private static ResourceLocation modern(String relativePath) {
        return new ResourceLocation(ASSET_NAMESPACE, "textures/" + TEXTURE_SUBDIR + relativePath);
    }

    private static ResourceLocation placeholder() {
        return new ResourceLocation(ASSET_NAMESPACE, "textures/vn/missing.png");
    }

    private static boolean exists(ResourceManager manager, ResourceLocation rl) {
        try {
            return manager.getResource(rl).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ResourceManager manager() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : mc.getResourceManager();
    }
}
