package com.github.tartaricacid.touhoulittlemaid.vn.controller;

import com.github.tartaricacid.touhoulittlemaid.vn.VnMod;
import com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules.PlayerInventoryHandler;
import com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules.SaveHandler;
import com.github.tartaricacid.touhoulittlemaid.vn.data.DialogueState;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules.DialogueHandler.*;
import static com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules.SaveHandler.processFinishing;
import static com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules.SpriteHandler.removeSprite;
import static com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules.SpriteHandler.updateSprite;
import static com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules.StateHandler.*;
import static com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules.VariableHandler.modifyVariable;


// I should really refactor this before I regret  everything...
public class VisualNovelEngine {

    public AtomicBoolean shutdown = new AtomicBoolean(false);
    public List<Map<String, Object>> gameData;
    public List<Map<String, Object>> globalSave;
    public List<Map<String, Object>> localSave;
    public AtomicLong currentState = new AtomicLong(0);
    public Map<String, Object> localVariables = new HashMap<>();

    public Map<String, Object> globalVariables = new HashMap<>();

    public DialogueState state;
    public AtomicBoolean isEngineRunning = new AtomicBoolean(false);

    public StringBuffer entityType = new StringBuffer();

    public StringBuffer entityName = new StringBuffer();

    public StringBuffer uid = new StringBuffer();

    public AtomicBoolean isDay = new AtomicBoolean(true);

    public PlayerInventoryHandler inventoryHandler;

    /**
     * Id of the scene currently loaded, e.g. {@code cupa/main}. Set by
     * {@code SceneLauncher}; used to resolve relative {@code file} jumps.
     */
    public String currentSceneId = null;

    /**
     * Primary constructor. State is no longer owned by the engine — it lives in
     * {@link com.github.tartaricacid.touhoulittlemaid.vn.state.VnState}, so
     * separate scene files can share variables.
     */
    public VisualNovelEngine(
            List<Map<String, Object>> gameData,
            String entityType,
            String entityName,
            String uid,
            boolean day,
            PlayerInventoryHandler inventory
    ) {
        this.uid.setLength(0);
        this.uid.append(uid);

        this.gameData = gameData;
        this.state = new DialogueState(null, null, null);
        this.entityName.setLength(0);
        this.entityName.append(entityName);
        this.entityType.setLength(0);
        this.entityType.append(entityType);
        this.isDay.set(day);
        this.inventoryHandler = inventory;
    }

    /**
     * Legacy constructor kept for the old save-based call sites. The
     * {@code globalSave}/{@code localSave} lists are ignored; durable state is
     * handled by VnState.
     */
    @Deprecated
    public VisualNovelEngine(
            List<Map<String, Object>> gameData,
            String entityType,
            String entityName,
            String uid,
            boolean day,
            PlayerInventoryHandler inventory,
            List<Map<String, Object>> globalSave,
            List<Map<String, Object>> localSave
    ) {
        this(gameData, entityType, entityName, uid, day, inventory);
    }

    // Look, for the sake of my own sanity, I have to refactor this thing...

    @SuppressWarnings("unchecked")
    private void processAction(Map<String, Object> action) {
        String actionType = (String) action.get("type");

        switch (actionType) {
            case "show_sprite":
                updateSprite(action, this);
                return;
            case "remove_sprite":
                removeSprite((String) action.get("sprite"), this);
                return;
            case "dialogue":
                String sound = (String) action.get("voice");
                updateDialogue(
                        (String) action.get("label"),
                        (String) action.get("content"),
                        (String) action.get("voice"),
                        this);
                return;
            case "modify_variable":
                modifyVariable(
                        (String) action.get("var"),
                        (String) action.get("action"),
                        action.get("value"),
                        (String) action.get("scope"),
                        currentState
                );
                break;
            case "modify_global":
                // Legacy action name. Reads/writes the WORLD scope explicitly so
                // old scripts keep working even if they omit a scope field.
                modifyVariable(
                        (String) action.get("var"),
                        (String) action.get("action"),
                        action.get("value"),
                        "WORLD",
                        currentState
                );
                break;
            case "give_item":
                inventoryHandler.giveItemToPlayer((String) action.get("item"), (int) (long) action.get("amount"));
                break;
            case "conditional":
                processConditional(action, this);
                break;
            case "conditional_global":
                processGlobalConditional(action, this);
                break;
            case "transition": {
                String transitionAction = (String) action.get("action");
                Object fileField = action.get("file");
                if ("jump".equals(transitionAction)) {
                    if (fileField instanceof String target && !target.isBlank()) {
                        // Cross-scene jump: swap the scene, then jump to the label
                        // inside it. This is what lets a story be many files.
                        jumpToScene(target, (String) action.get("label"));
                    } else {
                        processJump(action, this);
                    }
                } else if ("exit".equals(transitionAction)) {
                    processFinishing(this);
                    return;
                }
                break;
            }
            case "choice":
                updateChoices(
                        (List<Map<String, Object>>) action.get("choice"), this
                );
                break;
            case "command":
                updateCommand(action, state);
                this.currentState.incrementAndGet();
                break;
            case "label":
                this.currentState.incrementAndGet();
                break;
            case "modify_background":
                updateBackground((String) action.get("background"),this);
                break;
            case "clear_background":
                state.clearBackground();
                this.currentState.incrementAndGet();
                break;
            case "night_choice":
                if (!isDay.get()) {
                    updateChoices((List<Map<String, Object>>) action.get("choice"), this);
                } else {
                    this.currentState.incrementAndGet();
                }
                break;
            case "unlock_dialogues": {
                Set<String> events = new HashSet<>((List<String>) this.localVariables.getOrDefault("unlocked_events", new ArrayList<>()));
                Object incoming = action.get("events");
                if (incoming instanceof List<?> list) {
                    for (Object o : list) {
                        if (o != null) {
                            events.add(String.valueOf(o));
                        }
                    }
                }
                this.localVariables.put("unlocked_events", new ArrayList<>(events));
                this.currentState.incrementAndGet();
                break;
            }
            case "play_sound":
                updateSound(this, (String) action.get("sound"));
                // FIX: was falling through into "play_music", so every sound
                // effect also ran music logic.
                this.currentState.incrementAndGet();
                break;
            case "play_music":
                if (action.get("music") != null) {
                    updateMusic(this, (String) action.get("music"));
                } else {
                    stopMusic(this);
                }
                this.currentState.incrementAndGet();
                break;
            case "next":
                processNext(action,this);
                this.currentState.incrementAndGet();
                break;
            case "idle_chat":
                processIdleChat(this);
                break;
            case "finish_dialogue":
                processFinishing(this);
                // FIX: was falling through into "default", which bumped
                // currentState AFTER shutdown. That is the phantom-advance /
                // "click twice" bug.
                return;
            case "check_inventory":
                this.currentState.incrementAndGet();
                break;
            default:
                VnMod.warn("Unknown action type '" + actionType + "' at id " + action.get("id") + ", skipping");
                this.currentState.incrementAndGet();
                break;
        }
    }



    /**
     * Swap the running scene to another file and jump to a label inside it.
     * <p>
     * This is the multi-file story mechanic: {@code cupa/main} can hand off to
     * {@code cupa/night_01}, which writes maid/world state and hands back. The
     * engine instance survives so the screen, sprites and music keep their
     * state; only {@code gameData} and {@code currentState} change.
     * <p>
     * If the target scene or label is missing we log and stop the engine rather
     * than silently continuing into the wrong scene.
     */
    public void jumpToScene(String targetSceneId, String label) {
        List<Map<String, Object>> loaded = com.github.tartaricacid.touhoulittlemaid.vn.state.SceneRegistry.load(targetSceneId);
        if (loaded.isEmpty()) {
            VnMod.warn("Cross-scene jump failed, no scene at '" + targetSceneId + "'");
            processFinishing(this);
            return;
        }
        this.gameData = loaded;
        this.currentSceneId = targetSceneId;

        if (label == null || label.isBlank()) {
            this.currentState.set(0);
            return;
        }
        Long id = findLabelId(label, loaded);
        if (id == null) {
            VnMod.warn("Label '" + label + "' not found in scene '" + targetSceneId + "', starting at 0");
            this.currentState.set(0);
            return;
        }
        this.currentState.set(id);
        this.currentState.incrementAndGet();
    }

    public void runEngine() {
        while (isEngineRunning.get()) { // Infinite loop
            // Check if engine is running
            System.out.println(this.currentState);
            Map<String, Object> action = getDictById(this.currentState.get(),gameData);
            if(action == null){
                shutdown.set(true);
                isEngineRunning.set(false);
                return;
            }
            if ("meta".equals(((Map<?, ?>) action).get("type"))) {
                 processMeta(action,this);
            } else {
                processAction(action);
            }

        }
    }

    public DialogueState getNext() {
        return this.state;
    }

    public void buttonPress(String choice) {
        changeStateByLabel(choice,currentState,gameData);
        this.state.setChoices(new ArrayList<>());
    }
}
