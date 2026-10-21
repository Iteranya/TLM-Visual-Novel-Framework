import com.github.tartaricacid.touhoulittlemaid.vn.state.VnScope;
import com.github.tartaricacid.touhoulittlemaid.vn.state.VnState;
import net.minecraft.nbt.CompoundTag;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Scene-scoped variables used to be thrown away on every scene open. The 2024
 * engine persisted them per entity and restored them on load, so this pins the
 * restored behaviour: a bare name survives a detach/attach cycle.
 *
 * <p>These run without a game instance because {@link VnState} only needs a
 * {@link CompoundTag} standing in for a maid's persistent data.
 */
public class VnStatePersistenceTest {

    private CompoundTag maidTag;

    /** Simulates the maid going away and the same maid coming back. */
    private void cycleScene() {
        VnState.clearScene();
        VnState.detachMaid();
        VnState.attachMaid(maidTag);
    }

    @Before
    public void setUp() {
        maidTag = new CompoundTag();
        VnState.clearScene();
        VnState.detachMaid();
        VnState.attachMaid(maidTag);
    }

    @After
    public void tearDown() {
        VnState.detachMaid();
        VnState.clearScene();
    }

    @Test
    public void bareVariableSurvivesSceneClose() {
        VnState.set(VnScope.SCENE, "visits", 1);

        // Closing a scene clears the in-memory map and detaches the entity,
        // which is exactly what happens between two conversations.
        VnState.clearScene();
        VnState.detachMaid();

        // Reopening: new scene, same maid.
        VnState.attachMaid(maidTag);

        assertEquals(Integer.valueOf(1), VnState.get(VnScope.SCENE, "visits", null));
    }

    @Test
    public void bareVariableAccumulatesAcrossConversations() {
        VnState.set(VnScope.SCENE, "visits", 0);

        for (int expected = 1; expected <= 3; expected++) {
            VnState.addInt(VnScope.SCENE, "visits", 1);
            cycleScene();
            assertEquals(expected, VnState.getInt(VnScope.SCENE, "visits", -1));
        }
    }

    @Test
    public void bareAndMaidScopesDoNotCollide() {
        VnState.set(VnScope.SCENE, "affection", 5);
        VnState.set(VnScope.MAID, "affection", 99);

        assertEquals(5, VnState.getInt(VnScope.SCENE, "affection", -1));
        assertEquals(99, VnState.getInt(VnScope.MAID, "affection", -1));
    }

    @Test
    public void checkpointSurvivesSceneClose() {
        VnState.setCheckpoint("counting");
        cycleScene();
        assertEquals("counting", VnState.getCheckpoint());
    }

    @Test
    public void checkpointCanBeCleared() {
        VnState.setCheckpoint("counting");
        VnState.clearCheckpoint();
        assertNull(VnState.getCheckpoint());
    }

    @Test
    public void blankCheckpointIsTreatedAsNone() {
        VnState.setCheckpoint("");
        assertNull(VnState.getCheckpoint());
    }

    @Test
    public void worldScopeUnaffectedBySceneClear() {
        VnState.set(VnScope.WORLD, "affection", 42);
        VnState.clearScene();
        assertEquals(42, VnState.getInt(VnScope.WORLD, "affection", -1));
    }
}
