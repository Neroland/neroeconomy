package za.co.neroland.neroeconomy.api;

/** Server-side price adjustment. Implementations must be deterministic and side-effect-free. */
@FunctionalInterface
public interface PriceModifier {
    /** Returns a multiplier; invalid/non-positive values are ignored. */
    double multiplier(PriceContext context);
}
