package com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules;

import com.github.tartaricacid.touhoulittlemaid.vn.VnMod;
import com.github.tartaricacid.touhoulittlemaid.vn.controller.VisualNovelEngine;
import com.github.tartaricacid.touhoulittlemaid.vn.data.SpriteState;
import com.github.tartaricacid.touhoulittlemaid.vn.state.VnState;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class SpriteHandler {

    public static void removeSprite(String remove,VisualNovelEngine vn){
        removeSpriteByFolder(vn.state.getSprites(), remove);
        vn.currentState.incrementAndGet();
    }

    public static void updateSprite(Map<String, Object> sprite, VisualNovelEngine vn) {
        String spritePos;
        if(sprite.get("position")==null){
            spritePos = "CUSTOM";
        }
        else{
            spritePos = (String) sprite.get("position");
        }
        SpriteState newSprite;
        newSprite = (new SpriteState(
                (String) sprite.get("sprite"),
                resolveLocation(sprite),
                spritePos
        ));

        if(Objects.equals((String) sprite.get("action"), "show")){
            if(sprite.get("wRatio")!=null){
                newSprite.setPositioning(
                        ((Long) sprite.get("wRatio")).intValue(),
                        ((Long) sprite.get("hRatio")).intValue(),
                        ((Long) sprite.get("wFrameRatio")).intValue(),
                        ((Long) sprite.get("hFrameRatio")).intValue(),
                        ((Long) sprite.get("column")).intValue(),
                        ((Long) sprite.get("row")).intValue()
                );
            }
            for (SpriteState oldSprite: vn.state.getSprites()) {

                if(Objects.equals(oldSprite.getSprite(), newSprite.getSprite())){
                    removeSpriteByFolder(vn.state.getSprites(), newSprite.getSprite());
                    break;
                }
            }
            //System.out.println("Adding New Sprite: " + newSprite.getSprite());
            vn.state.addSprite(newSprite);
        }
        vn.currentState.incrementAndGet();
    }
    /**
     * Choose the texture path for a show_sprite action.
     *
     * <p>The SDK emits two paths for every sprite:
     *
     * <pre>
     *   location     characters/&lt;id&gt;/&lt;outfit&gt;/&lt;emotion&gt;.png
     *   dyn_location characters/&lt;id&gt;/&lt;&lt;outfit_var&gt;&gt;/&lt;emotion&gt;.png
     * </pre>
     *
     * <p>{@code dyn_location} lets a character change outfit mid-story: the
     * {@code <...>} placeholder is substituted from scene and world state at the
     * moment the sprite is shown. When the substitution succeeds and the result
     * differs, it wins; otherwise the static {@code location} is used. This
     * mirrors {@code _parseString} in the browser viewer, which already
     * implements it. Without it, a script that swaps outfits silently keeps the
     * default art.
     */
    private static String resolveLocation(Map<String, Object> sprite) {
        String staticLocation = (String) sprite.get("location");
        Object dynRaw = sprite.get("dyn_location");

        if (!(dynRaw instanceof String dyn) || dyn.isBlank()) {
            return staticLocation;
        }

        String resolved = interpolate(dyn);
        if (resolved.equals(dyn) || resolved.contains("<")) {
            // Nothing substituted, or only partly: the dynamic path refers to a
            // variable that is not set, so it cannot be trusted.
            VnMod.warn("dyn_location '" + dyn + "' did not fully resolve, using location '" + staticLocation + "'");
            return staticLocation;
        }
        return resolved;
    }

    /**
     * Replace {@code <name>} with a scene or world variable's value.
     *
     * <p>Bare names resolve through the same scope chain a script uses, so a
     * placeholder can read a local flag or a world flag without caring which.
     * Unknown names are left untouched so {@link #resolveLocation} can tell the
     * substitution failed.
     */
    private static String interpolate(String template) {
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('<', i);
            if (open < 0) {
                out.append(template, i, template.length());
                break;
            }
            int close = template.indexOf('>', open + 1);
            if (close < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, open);
            String name = template.substring(open + 1, close);
            Object value = VnState.get(name, null);
            if (value == null) {
                out.append('<').append(name).append('>');
            } else {
                out.append(value);
            }
            i = close + 1;
        }
        return out.toString();
    }

    public static void removeSpriteByFolder(List<SpriteState> sprites, String folderName) {
        sprites.removeIf(sprite -> sprite.getSprite().equals(folderName));
    }
}
