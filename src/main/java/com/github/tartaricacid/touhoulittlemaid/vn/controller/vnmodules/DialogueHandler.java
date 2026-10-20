package com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules;

import com.github.tartaricacid.touhoulittlemaid.vn.VnMod;
import com.github.tartaricacid.touhoulittlemaid.vn.controller.VisualNovelEngine;
import com.github.tartaricacid.touhoulittlemaid.vn.state.VnScope;
import com.github.tartaricacid.touhoulittlemaid.vn.state.VnState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules.SaveHandler.processFinishing;
import static com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules.StateHandler.findLabelId;
import static com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules.VariableHandler.createVariable;

public class DialogueHandler {

    /**
     * Standard conditional against the SCENE / MAID scopes.
     * <p>
     * Variables are read through {@link VnState}, so a bare name resolves to the
     * scene scope (backwards compatible) while {@code @maid.flag} and
     * {@code @world.affection.cupa} reach persistent state.
     */
    public static void processConditional(Map<String, Object> condition, VisualNovelEngine vn) {
        evaluate(condition, vn, null);
    }

    /**
     * Legacy {@code conditional_global}: forces the WORLD scope.
     */
    public static void processGlobalConditional(Map<String, Object> condition, VisualNovelEngine vn) {
        evaluate(condition, vn, "WORLD");
    }

    @SuppressWarnings("unchecked")
    private static void evaluate(Map<String, Object> condition, VisualNovelEngine vn, String forcedScope) {
        String conditionType = String.valueOf(condition.get("condition"));
        String rawVar = (String) condition.get("var");
        String scopeField = forcedScope != null ? forcedScope : (String) condition.get("scope");
        VnScope.Resolved target = VnScope.resolve(rawVar, scopeField);

        Object var = VnState.get(target.scope(), target.name(), null);
        Object value = condition.get("value");
        Object endObj = condition.get("end");
        long end = endObj instanceof Number n ? n.longValue() : -1L;

        boolean result = switch (conditionType) {
            case "equal" -> compareEquals(var, value);
            case "not_equal" -> !compareEquals(var, value);
            case "less_than" -> bothNumbers(var, value) && num(var) < num(value);
            case "greater_than" -> bothNumbers(var, value) && num(var) > num(value);
            case "less_or_equal" -> bothNumbers(var, value) && num(var) <= num(value);
            case "greater_or_equal" -> bothNumbers(var, value) && num(var) >= num(value);
            case "night" -> !vn.isDay.get();
            case "day" -> vn.isDay.get();
            case "exists" -> var != null;
            case "not_exists" -> var == null;
            default -> {
                VnMod.warn("Unknown condition '" + conditionType + "' at id " + condition.get("id") + ", treating as false");
                yield false;
            }
        };

        if (result) {
            vn.currentState.incrementAndGet();
        } else if (end >= 0) {
            vn.currentState.set(end);
        } else {
            // Malformed conditional with no end target: fail safe by advancing
            // rather than looping on the same action forever.
            VnMod.warn("Conditional at id " + condition.get("id") + " has no valid 'end', advancing");
            vn.currentState.incrementAndGet();
        }
    }

    private static boolean bothNumbers(Object a, Object b) {
        return a instanceof Number && b instanceof Number;
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    /**
     * Structural equality across the Number/Boolean/String shapes Gson and NBT
     * can hand back. Previously {@code 1} and {@code true} compared unequal and
     * a JSON double 5.0 never matched an int 5.
     */
    private static boolean compareEquals(Object a, Object b) {
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        if (a instanceof Boolean ba && b instanceof Number nb) {
            return (ba ? 1 : 0) == nb.doubleValue();
        }
        if (a instanceof Number na && b instanceof Boolean bb) {
            return na.doubleValue() == (bb ? 1 : 0);
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    public static void processJump(Map<String, Object> action, VisualNovelEngine vn) {
        vn.currentState.set(findLabelId((String) action.get("label"),vn.gameData));
        vn.currentState.incrementAndGet(); //TO-DO: Figure out if this is necessary (Update: Yes It Is)
    }

    public static void processMeta(Map<String, Object> action,VisualNovelEngine vn) {
        String actionType = (String) action.get("action");
        if ("create_var".equals(actionType)) {
            createVariable((String) action.get("var"), action.get("init"),vn.localVariables,vn.currentState);
        }
        else if("create_global".equals(actionType)){
            createVariable((String) action.get("var"), action.get("init"),vn.globalVariables,vn.currentState);
        }else {
            vn.currentState.incrementAndGet();
        }
    }

    public static void processNext(Map<String, Object> action,VisualNovelEngine vn) {
        vn.localVariables.put("checkpoint",action.get("label"));
    }

    public static void processIdleChat(
            VisualNovelEngine vn
    ){
        // Alright, Null Handling Time
        // Fuck...
        //System.out.println(vn.globalVariables.get("unlocked_events"));
        List<String> chats = (List<String>) vn.localVariables.getOrDefault("unlocked_events", new ArrayList<>());
        if (!chats.isEmpty()) {

            Random random = new Random();
            String chat = chats.get(random.nextInt(chats.size()));
            System.out.println(chat);
            vn.currentState.set(findLabelId(chat,vn.gameData));
            vn.currentState.incrementAndGet();
        } else {
            processFinishing(vn);
        }

    }


}
