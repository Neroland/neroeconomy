package za.co.neroland.neroeconomy.client.demo;

import java.util.List;

/**
 * One step of a "Show me" walkthrough. A step is shown on a fresh sample view: the demo replays
 * every step up to and including this one (switching to each step's sample page and doing its
 * actions), so Back always shows exactly what it showed before. Pure Java.
 *
 * @param target  what the step's text talks about; null for a text-only step
 * @param page    the index of the sample page this step switches to, or -1 to keep the one shown
 * @param actions what is done to the view at this step, in order
 */
public record DemoStep(DemoTarget target, int page, List<DemoAction> actions) {
    public DemoStep {
        actions = List.copyOf(actions);
    }

    /** A step that points at {@code target} after doing {@code actions}. */
    public static DemoStep at(DemoTarget target, DemoAction... actions) {
        return new DemoStep(target, -1, List.of(actions));
    }

    /** A step that first switches to sample page {@code page}. */
    public static DemoStep on(int page, DemoTarget target, DemoAction... actions) {
        return new DemoStep(target, page, List.of(actions));
    }

    /** A step with words only. */
    public static DemoStep text() { return new DemoStep(null, -1, List.of()); }
}
