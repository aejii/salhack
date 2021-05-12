package me.ionar.salhack.events.minecraft;

import net.minecraft.client.multiplayer.WorldClient;

public class WorldLoadEvent {
    public WorldClient Client;

    public WorldLoadEvent(WorldClient client) {
        Client = client;
    }
}
