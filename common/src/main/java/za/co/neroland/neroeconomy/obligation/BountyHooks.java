package za.co.neroland.neroeconomy.obligation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** Loader event bridge for server-verified bounty completion. */
public final class BountyHooks {
    private BountyHooks() { }
    public static void entityKilled(LivingEntity victim, DamageSource source) {
        if (victim == null || source == null) return;
        if (source.getEntity() instanceof ServerPlayer killer && killer.level().getServer() != null)
            ObligationSavedData.get(killer.level().getServer()).onKill(killer, victim);
    }
}
