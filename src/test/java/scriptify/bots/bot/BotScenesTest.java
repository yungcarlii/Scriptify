package scriptify.bots.bot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import scriptify.bots.npc.BotIdentity;
import scriptify.bots.npc.MovementReplay;
import scriptify.bots.npc.NpcSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A saved scene has to come back from {@code scenes.json} with everything a restore puts back. */
class BotScenesTest {

    @Test
    void sceneSurvivesTheFile() {
        BotScenes.SceneBot bot = new BotScenes.SceneBot();
        bot.spot = new BotManager.BotData();
        bot.spot.name = "Guard_1";
        bot.spot.x = 10.5;
        bot.spot.y = 64;
        bot.spot.z = -3.25;
        bot.spot.yaw = 90f;
        bot.spot.pitch = -10f;
        bot.spot.dimension = "minecraft:overworld";
        bot.spot.gamemode = "survival";
        bot.items.put("0", "{count:1,id:\"minecraft:iron_sword\"}");
        bot.skin = new BotIdentity.Skin("value", "signature");
        bot.faction = "red";
        bot.group = "guards";
        bot.settings = new NpcSettings();
        bot.settings.crouching = true;
        bot.settings.action = NpcSettings.Action.SWING;
        bot.path = "edit_guard_1";
        bot.pathPoint = 2;
        bot.combatPath = true;
        bot.sightRange = 30;
        bot.route = new BotPath.PathData("edit_guard_1");
        bot.route.points = List.of(new Vec3(1, 64, 1), new Vec3(5, 64, 9));
        bot.route.loop = true;
        bot.leader = "Steve";
        bot.wander = true;
        bot.replay = List.of(new MovementReplay.Frame(1, 2, 3, 4f, 5f, true),
            new MovementReplay.Frame(6, 7, 8, 9f, 10f, false));
        bot.replayCursor = 1;
        bot.scenario = 3;

        BotScenes.Scene scene = new BotScenes.Scene();
        scene.name = "City";
        scene.dimension = "minecraft:overworld";
        scene.x = 100;
        scene.radius = 48;
        scene.bots.add(bot);
        Map<String, BotScenes.Scene> scenes = new LinkedHashMap<>();
        scenes.put("city", scene);

        BotScenes.Scene back = BotScenes.fromJson(BotScenes.toJson(scenes)).get("city");
        assertNotNull(back);
        assertEquals("City", back.name);
        assertEquals(48, back.radius);
        BotScenes.SceneBot got = back.bots.get(0);
        assertEquals("Guard_1", got.spot.name);
        assertEquals(-3.25, got.spot.z);
        assertEquals(90f, got.spot.yaw);
        assertEquals("{count:1,id:\"minecraft:iron_sword\"}", got.items.get("0"));
        assertEquals(new BotIdentity.Skin("value", "signature"), got.skin);
        assertEquals("red", got.faction);
        assertEquals("guards", got.group);
        assertTrue(got.settings.crouching());
        assertEquals(NpcSettings.Action.SWING, got.settings.action());
        assertNull(got.settings.hittable, "an unset override must stay unset, not pin the default");
        assertEquals("edit_guard_1", got.path);
        assertEquals(2, got.pathPoint);
        assertTrue(got.combatPath);
        assertEquals(new Vec3(5, 64, 9), got.route.points.get(1));
        assertTrue(got.route.loop);
        assertEquals("Steve", got.leader);
        assertTrue(got.wander);
        assertEquals(bot.replay, got.replay);
        assertEquals(1, got.replayCursor);
        assertEquals(3, got.scenario);
    }

    @Test
    void brokenEntriesAreDroppedNotFatal() {
        String json = "{\"a\":null,\"b\":{\"name\":\"b\",\"bots\":[null,{\"items\":null},"
            + "{\"spot\":{\"name\":\"Ok\"}}]}}";
        Map<String, BotScenes.Scene> scenes = BotScenes.fromJson(json);
        assertEquals(List.of("b"), List.copyOf(scenes.keySet()));
        assertEquals(1, scenes.get("b").bots.size());
        assertEquals("Ok", scenes.get("b").bots.get(0).spot.name);
        assertNotNull(scenes.get("b").bots.get(0).items);
    }
}
