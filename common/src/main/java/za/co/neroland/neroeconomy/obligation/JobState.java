package za.co.neroland.neroeconomy.obligation;

import java.util.Locale;

/** Lifecycle of a job or auction. Constants are only ever appended: the name is the saved form. */
public enum JobState {
    OPEN, CLAIMED, FULFILLED, EXPIRED;

    /** Lang key of the word a player is shown for this state; the enum name itself is never displayed. */
    public String translationKey() {
        return "command.neroeconomy.job_state." + name().toLowerCase(Locale.ROOT);
    }
}
