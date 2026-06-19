package com.grokvision;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("grokvision")
public interface GrokVisionConfig extends Config
{
    @ConfigItem(
        keyName = "port",
        name = "HTTP Port",
        description = "Local port for the Grok data server (restart plugin after change)",
        position = 0
    )
    default int port()
    {
        return 5678;
    }

    @ConfigItem(
        keyName = "enableNpcs",
        name = "Include NPCs",
        description = "Expose NPC positions, names and click points",
        position = 1
    )
    default boolean enableNpcs()
    {
        return true;
    }

    @ConfigItem(
        keyName = "enableObjects",
        name = "Include Objects",
        description = "Expose GameObjects, GroundObjects, etc. (trees, rocks, ladders, etc.)",
        position = 2
    )
    default boolean enableObjects()
    {
        return true;
    }

    @ConfigItem(
        keyName = "enableGroundItems",
        name = "Include Ground Items",
        description = "Expose dropped items on the ground",
        position = 3
    )
    default boolean enableGroundItems()
    {
        return true;
    }

    @ConfigItem(
        keyName = "enablePlayer",
        name = "Include Player",
        description = "Expose local player position data",
        position = 4
    )
    default boolean enablePlayer()
    {
        return true;
    }

    @ConfigItem(
        keyName = "maxDistance",
        name = "Max Distance (tiles)",
        description = "Only include entities within this many tiles of the player (0 = unlimited)",
        position = 5
    )
    @Range(min = 0, max = 50)
    default int maxDistance()
    {
        return 15;
    }

    @ConfigItem(
        keyName = "updateOnTick",
        name = "Update every game tick",
        description = "Refresh data on every game tick (recommended)",
        position = 6
    )
    default boolean updateOnTick()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showOverlay",
        name = "Debug overlay",
        description = "Show a small on-screen debug panel with connection info",
        position = 7
    )
    default boolean showOverlay()
    {
        return false;
    }
}