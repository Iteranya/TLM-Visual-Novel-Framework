package com.github.tartaricacid.touhoulittlemaid.vn.controller.vnmodules;

import com.github.tartaricacid.touhoulittlemaid.vn.VnMod;
import com.github.tartaricacid.touhoulittlemaid.vn.state.VnScope;
import com.github.tartaricacid.touhoulittlemaid.vn.state.VnState;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Variable reads/writes for the VN engine.
 * <p>
 * Rewritten to route through {@link VnState} instead of a bare
 * {@code Map<String,Object>}. That is what makes separate scene files able to
 * share state: a scene writes {@code @world.affection.cupa} and the next scene
 * reads it back, because both hit the same store instead of a map that dies
 * with the engine instance.
 * <p>
 * Bugs fixed relative to the original MobTalker version:
 * <ul>
 *     <li>{@code increment_var} on an undeclared variable silently did nothing;
 *         it now starts from 0.</li>
 *     <li>{@code subtract_var} is canonical (the old terminal runtime used the
 *         typo {@code substract_var}, so Java and the Python player disagreed).
 *         Both spellings are accepted here.</li>
 *     <li>No more silent no-op when the stored value is a String.</li>
 * </ul>
 */
public class VariableHandler {

    public static void modifyVariable(String variable,
                                      String operation,
                                      Object value,
                                      String explicitScope,
                                      AtomicLong currentState) {
        VnScope.Resolved target = VnScope.resolve(variable, explicitScope);
        String key = target.name();
        String op = operation == null ? "set" : operation.toLowerCase();

        switch (op) {
            case "increment_var", "increment", "add" -> {
                double current = VnState.getDouble(target.scope(), key, 0);
                VnState.set(target.scope(), key, normalize(current + toDouble(value, 0)));
            }
            case "subtract_var", "substract_var", "subtract", "sub" -> {
                double current = VnState.getDouble(target.scope(), key, 0);
                VnState.set(target.scope(), key, normalize(current - toDouble(value, 0)));
            }
            case "set", "modify_var" -> VnState.set(target.scope(), key, value);
            default -> {
                VnMod.warn("Unknown variable operation '" + operation + "', treating as set");
                VnState.set(target.scope(), key, value);
            }
        }

        currentState.incrementAndGet();
    }

    /**
     * Legacy entry point for call sites that still pass a map. Kept so the
     * ported engine compiles during migration; new code should use the
     * scope-aware overload.
     */
    public static void modifyVariable(String variable,
                                      String operation,
                                      Object value,
                                      Map<String, Object> legacyVariables,
                                      AtomicLong currentState) {
        VnScope.Resolved target = VnScope.resolve(variable);
        modifyVariable(variable, operation, value, target.scope().name(), currentState);
        if (legacyVariables != null) {
            legacyVariables.put(variable, VnState.get(target.scope(), target.name(), value));
        }
    }

    public static void createVariable(String varName,
                                      Object varInit,
                                      String explicitScope,
                                      AtomicLong currentState) {
        VnScope.Resolved target = VnScope.resolve(varName, explicitScope);
        // Do not clobber an existing saved value on scene re-entry: replaying a
        // scene must not reset the player's progress.
        if (VnState.get(target.scope(), target.name(), null) == null) {
            VnState.set(target.scope(), target.name(), varInit);
        }
        currentState.incrementAndGet();
    }

    public static void createVariable(String varName,
                                      Object varInit,
                                      Map<String, Object> legacyVariables,
                                      AtomicLong currentState) {
        VnScope.Resolved target = VnScope.resolve(varName);
        createVariable(varName, varInit, target.scope().name(), currentState);
        if (legacyVariables != null) {
            legacyVariables.put(varName, VnState.get(target.scope(), target.name(), varInit));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static double toDouble(Object value, double def) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    /**
     * Keep integer variables integral. Gson round-trips a Double as 5.0, which
     * makes {@code equal} comparisons against 5 fail.
     */
    private static Object normalize(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)
                && v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
            return (int) v;
        }
        return v;
    }
}
