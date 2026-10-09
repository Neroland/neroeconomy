package za.co.neroland.neroeconomy.client;

import java.util.UUID;

import za.co.neroland.neroeconomy.hub.HubAction;

/**
 * Where a hub tab view's intents go. The Economy Hub hands its views the live sender, which asks
 * the server; a "Show me" demo hands its views {@link za.co.neroland.neroeconomy.client.demo.DemoIntents},
 * a different object that sends nothing. A view holds exactly one and has no other way to reach the
 * server. Pure Java.
 */
public interface HubIntents {
    /**
     * One intent about the tab on screen.
     *
     * @param action what the player did
     * @param target the row it is about, or null
     * @param a      the action's first number
     * @param b      the action's second number
     * @param text   the action's text, never null
     */
    void send(HubAction action, UUID target, long a, long b, String text);
}
