package com.grokvision;

import com.google.inject.Provides;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Grok-O-Vision
 *
 * Exposes detailed, named, positioned game entities (NPCs, objects like trees/rocks/ladders,
 * ground items, player) over a local HTTP endpoint for the osrs-grok-bridge.
 *
 * This replaces vision-only guessing for "where is the X I need to click".
 *
 * Expand by adding more collectors or fields here. Python side consumes the JSON.
 */
@PluginDescriptor(
    name = "GrokVision",
    description = "Provides accurate entity position + name data via local HTTP for the Grok OSRS bridge",
    tags = {"grok", "vision", "bridge", "automation", "data", "npc", "object"}
)
@Slf4j
public class GrokVisionPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private GrokVisionConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private GrokVisionOverlay overlay;

    private HttpServer server;
    private final AtomicReference<Map<String, Object>> currentState = new AtomicReference<>(Collections.emptyMap());

    private int lastTick = -1;

    @Provides
    GrokVisionConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(GrokVisionConfig.class);
    }

    @Override
    protected void startUp() throws Exception
    {
        overlayManager.add(overlay);

        int port = config.port();
        try
        {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/grok/state", new StateHandler());
            server.createContext("/grok/health", new HealthHandler());
            server.setExecutor(Executors.newSingleThreadExecutor());
            server.start();
            log.info("Grok-O-Vision HTTP server started on http://127.0.0.1:{}", port);
        }
        catch (IOException e)
        {
            log.error("Failed to start Grok-O-Vision HTTP server on port {}", port, e);
        }

        // Initial snapshot
        updateState();
    }

    @Override
    protected void shutDown() throws Exception
    {
        overlayManager.remove(overlay);

        if (server != null)
        {
            server.stop(0);
            log.info("Grok-O-Vision HTTP server stopped");
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (config.updateOnTick())
        {
            updateState();
        }
    }

    private void updateState()
    {
        if (client == null || client.getGameState() != GameState.LOGGED_IN)
        {
            currentState.set(Collections.singletonMap("error", "not_logged_in"));
            return;
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("tick", client.getTickCount());
        state.put("plane", client.getPlane());
        state.put("canvasWidth", client.getCanvasWidth());
        state.put("canvasHeight", client.getCanvasHeight());

        Player local = client.getLocalPlayer();
        if (config.enablePlayer() && local != null)
        {
            state.put("player", entityToMap(local, "player"));
        }

        int maxDist = config.maxDistance();

        if (config.enableNpcs())
        {
            List<Map<String, Object>> npcs = new ArrayList<>();
            for (NPC npc : client.getNpcs())
            {
                if (npc == null || npc.getName() == null) continue;
                if (maxDist > 0 && distanceToPlayer(npc) > maxDist) continue;
                npcs.add(entityToMap(npc, "npc"));
            }
            state.put("npcs", npcs);
        }

        if (config.enableObjects())
        {
            state.put("objects", collectObjects(maxDist));
        }

        if (config.enableGroundItems())
        {
            state.put("groundItems", collectGroundItems(maxDist));
        }

        currentState.set(state);
    }

    private List<Map<String, Object>> collectObjects(int maxDist)
    {
        List<Map<String, Object>> objects = new ArrayList<>();
        if (client.getScene() == null) return objects;

        Tile[][][] tiles = client.getScene().getTiles();
        int plane = client.getPlane();
        if (tiles == null || plane >= tiles.length) return objects;

        Tile[][] planeTiles = tiles[plane];
        if (planeTiles == null) return objects;

        WorldPoint playerWp = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;

        // Scan visible area - this is the main expensive part but acceptable
        for (Tile[] row : planeTiles)
        {
            if (row == null) continue;
            for (Tile tile : row)
            {
                if (tile == null) continue;

                // GameObjects (trees, rocks, ladders, most scenery)
                for (GameObject go : tile.getGameObjects())
                {
                    if (go == null || go.getId() == -1) continue;
                    if (maxDist > 0 && playerWp != null && distance(playerWp, go.getWorldLocation()) > maxDist) continue;
                    objects.add(objectToMap(go, tile, "game_object"));
                }

                // GroundObject (some resources, trapdoors etc.)
                GroundObject ground = tile.getGroundObject();
                if (ground != null && ground.getId() != -1)
                {
                    if (maxDist == 0 || playerWp == null || distance(playerWp, ground.getWorldLocation()) <= maxDist)
                        objects.add(objectToMap(ground, tile, "ground_object"));
                }

                // WallObject
                WallObject wall = tile.getWallObject();
                if (wall != null && wall.getId() != -1)
                {
                    if (maxDist == 0 || playerWp == null || distance(playerWp, wall.getWorldLocation()) <= maxDist)
                        objects.add(objectToMap(wall, tile, "wall_object"));
                }

                // DecorativeObject
                DecorativeObject deco = tile.getDecorativeObject();
                if (deco != null && deco.getId() != -1)
                {
                    if (maxDist == 0 || playerWp == null || distance(playerWp, deco.getWorldLocation()) <= maxDist)
                        objects.add(objectToMap(deco, tile, "decorative_object"));
                }
            }
        }
        return objects;
    }

    private List<Map<String, Object>> collectGroundItems(int maxDist)
    {
        List<Map<String, Object>> items = new ArrayList<>();
        if (client.getScene() == null) return items;

        Tile[][][] tiles = client.getScene().getTiles();
        int plane = client.getPlane();
        if (tiles == null || plane >= tiles.length) return items;

        WorldPoint playerWp = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;

        for (Tile[] row : tiles[plane])
        {
            if (row == null) continue;
            for (Tile tile : row)
            {
                if (tile == null) continue;
                for (TileItem item : tile.getGroundItems())
                {
                    if (item == null) continue;
                    if (maxDist > 0 && playerWp != null && distance(playerWp, tile.getWorldLocation()) > maxDist) continue;

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", "ground_item");
                    m.put("id", item.getId());
                    m.put("quantity", item.getQuantity());
                    m.put("worldLocation", worldPointToMap(tile.getWorldLocation()));
                    Point canvas = getCanvasPoint(tile.getWorldLocation());
                    m.put("canvasLocation", pointToMap(canvas));
                    m.put("clickPoint", pointToMap(estimateClickPoint(canvas)));
                    items.add(m);
                }
            }
        }
        return items;
    }

    // --- Entity serialization helpers (easy to extend) ---

    private Map<String, Object> entityToMap(Actor actor, String type)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("name", actor.getName());
        m.put("worldLocation", worldPointToMap(actor.getWorldLocation()));
        m.put("localLocation", localPointToMap(actor.getLocalLocation()));

        Point canvas = getActorCanvasLocation(actor);
        m.put("canvasLocation", pointToMap(canvas));

        // Recommended click point - biased a little lower for body/NPC interaction
        Point click = estimateActorClickPoint(actor, canvas);
        m.put("clickPoint", pointToMap(click));

        m.put("distance", client.getLocalPlayer() != null ? distanceToPlayer(actor) : 0);

        if (actor instanceof NPC)
        {
            NPC npc = (NPC) actor;
            m.put("id", npc.getId());
            NPCComposition comp = npc.getTransformedComposition();
            if (comp != null)
            {
                m.put("actions", comp.getActions());
                m.put("combatLevel", npc.getCombatLevel());
            }
        }
        return m;
    }

    private Map<String, Object> objectToMap(TileObject obj, Tile tile, String type)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", obj.getId());

        String name = "Unknown";
        String[] actions = null;

        try
        {
            if (obj instanceof GameObject)
            {
                GameObject go = (GameObject) obj;
                ObjectComposition def = client.getObjectDefinition(go.getId());
                if (def != null)
                {
                    name = def.getName();
                    actions = def.getActions();
                }
            }
            else if (obj instanceof GroundObject || obj instanceof WallObject || obj instanceof DecorativeObject)
            {
                // Most of these also resolve via getObjectDefinition
                ObjectComposition def = client.getObjectDefinition(obj.getId());
                if (def != null)
                {
                    name = def.getName();
                    actions = def.getActions();
                }
            }
        }
        catch (Exception ignored) {}

        m.put("name", name);
        if (actions != null) m.put("actions", actions);

        WorldPoint wp = obj.getWorldLocation();
        m.put("worldLocation", worldPointToMap(wp));
        m.put("localLocation", localPointToMap(obj.getLocalLocation()));

        Point canvas = getCanvasPoint(wp);
        m.put("canvasLocation", pointToMap(canvas));
        m.put("clickPoint", pointToMap(estimateClickPoint(canvas)));

        if (client.getLocalPlayer() != null)
        {
            m.put("distance", distance(client.getLocalPlayer().getWorldLocation(), wp));
        }
        return m;
    }

    private Map<String, Object> worldPointToMap(WorldPoint wp)
    {
        if (wp == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("x", wp.getX());
        m.put("y", wp.getY());
        m.put("plane", wp.getPlane());
        return m;
    }

    private Map<String, Object> localPointToMap(LocalPoint lp)
    {
        if (lp == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("x", lp.getX());
        m.put("y", lp.getY());
        return m;
    }

    private Map<String, Object> pointToMap(Point p)
    {
        if (p == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("x", p.getX());
        m.put("y", p.getY());
        return m;
    }

    private int distanceToPlayer(Actor a)
    {
        Player p = client.getLocalPlayer();
        if (p == null || a == null) return Integer.MAX_VALUE;
        return distance(p.getWorldLocation(), a.getWorldLocation());
    }

    private int distance(WorldPoint a, WorldPoint b)
    {
        if (a == null || b == null || a.getPlane() != b.getPlane()) return Integer.MAX_VALUE;
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    private Point getCanvasPoint(WorldPoint wp)
    {
        if (client == null || wp == null) return null;
        LocalPoint lp = LocalPoint.fromWorld(client, wp);
        if (lp == null) return null;
        return Perspective.localToCanvas(client, lp, wp.getPlane());
    }

    private Point getActorCanvasLocation(Actor actor)
    {
        if (client == null || actor == null) return null;
        LocalPoint lp = actor.getLocalLocation();
        if (lp == null) return null;
        // Use half the logical height to aim at the center of the actor (good for clicking body)
        int height = actor.getLogicalHeight() / 2;
        return Perspective.localToCanvas(client, lp, actor.getWorldLocation().getPlane(), height);
    }

    // Simple click point heuristics - improve over time
    private Point estimateActorClickPoint(Actor actor, Point canvasLoc)
    {
        if (canvasLoc == null) return null;
        // Bias a little down from the reported canvas location (usually head/center)
        return new Point(canvasLoc.getX(), canvasLoc.getY() + 25);
    }

    private Point estimateClickPoint(Point base)
    {
        if (base == null) return null;
        return new Point(base.getX(), base.getY() + 18);
    }

    private String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) {
            String s = (String) obj;
            s = s.replace("\\", "\\\\").replace("\"", "\\\"");
            return "\"" + s + "\"";
        }
        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }
        if (obj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<?, ?> map = (Map<?, ?>) obj;
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(e.getKey())).append(":").append(toJson(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof List) {
            @SuppressWarnings("unchecked")
            List<?> list = (List<?>) obj;
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : list) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(o));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + obj.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // --- HTTP handlers ---

    private class StateHandler implements HttpHandler
    {
        @Override
        public void handle(HttpExchange exchange) throws IOException
        {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()))
            {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            Map<String, Object> data = currentState.get();
            String json = toJson(data);

            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream os = exchange.getResponseBody())
            {
                os.write(bytes);
            }
        }
    }

    private class HealthHandler implements HttpHandler
    {
        @Override
        public void handle(HttpExchange exchange) throws IOException
        {
            String body = "{\"status\":\"ok\",\"tick\":" + client.getTickCount() + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody())
            {
                os.write(bytes);
            }
        }
    }
}