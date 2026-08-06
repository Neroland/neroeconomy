package za.co.neroland.neroeconomy.api;

import net.minecraft.server.level.ServerPlayer;
import za.co.neroland.neroeconomy.obligation.EconomyJob;
import za.co.neroland.neroeconomy.obligation.JobType;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;

/** String-keyed soft seam used by NeroLogistics when present; otherwise no caller invokes it. */
public final class DeliveryJobApi {
    private DeliveryJobApi() { }
    public static EconomyJob post(ServerPlayer issuer, String itemOrTag, int quantity, String destination,
            long payout, long expiresAt) {
        return ObligationSavedData.get(issuer.level().getServer()).postJob(issuer, JobType.DELIVERY,
                itemOrTag, quantity, destination, payout, expiresAt);
    }
}
