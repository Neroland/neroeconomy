package za.co.neroland.neroeconomy.market;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/**
 * The item-stack codec every stored economy row uses.
 *
 * <p>A stack that carries registry-backed data (an enchantment, a trim, a banner pattern) can only
 * be encoded or decoded with the game's registries at hand. The game's own save path supplies them;
 * Core's recovery backup, which is a second copy of the same store, does not. Without them one such
 * stack anywhere in the store would make the whole backup fail to write — and an erasure fail to
 * reach it. This codec supplies the running server's registries whenever the caller did not, and is
 * otherwise exactly the vanilla stack codec, so the saved form is unchanged.
 */
public final class StoredStacks {
    private static volatile HolderLookup.Provider registries;

    private StoredStacks() { }

    /** Makes the running server's registries available. Called when the economy store is opened. */
    public static void bind(HolderLookup.Provider provider) { registries = provider; }

    /** Server stop: the registries belong to the world that just closed. */
    public static void unbind() { registries = null; }

    public static final Codec<ItemStack> CODEC = new Codec<>() {
        @Override public <T> DataResult<T> encode(ItemStack stack, DynamicOps<T> ops, T prefix) {
            return ItemStack.CODEC.encode(stack, withRegistries(ops), prefix);
        }

        @Override public <T> DataResult<Pair<ItemStack, T>> decode(DynamicOps<T> ops, T input) {
            return ItemStack.CODEC.decode(withRegistries(ops), input);
        }

        @Override public String toString() { return "StoredStacks"; }
    };

    private static <T> DynamicOps<T> withRegistries(DynamicOps<T> ops) {
        HolderLookup.Provider known = registries;
        return ops instanceof RegistryOps || known == null ? ops : known.createSerializationContext(ops);
    }
}
