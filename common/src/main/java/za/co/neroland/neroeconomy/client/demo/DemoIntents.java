package za.co.neroland.neroeconomy.client.demo;

import java.util.UUID;

import za.co.neroland.neroeconomy.client.HubIntents;
import za.co.neroland.neroeconomy.hub.HubAction;

/**
 * The intent sender every demo view is given: it drops what it is handed. It is its own class and
 * its own object, never the hub's live sender, and nothing in this package can reach the network,
 * so a demo cannot move a credit or an item however its buttons are pressed. Pure Java.
 */
public final class DemoIntents implements HubIntents {
    /** The one demo sender. */
    public static final DemoIntents NONE = new DemoIntents();

    private DemoIntents() { }

    @Override
    public void send(HubAction action, UUID target, long a, long b, String text) {
        // A demo sends nothing: what was pressed only changes what the demo view shows.
    }
}
