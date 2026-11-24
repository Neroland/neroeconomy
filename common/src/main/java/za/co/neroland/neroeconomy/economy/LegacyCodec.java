package za.co.neroland.neroeconomy.economy;

//? if <1.20.5 {
/*import java.util.function.Supplier;

import com.google.common.base.Suppliers;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

public final class LegacyCodec {
    private LegacyCodec() { }

    public static <A> Codec<A> lazy(Supplier<Codec<A>> supplier) {
        var resolved = Suppliers.memoize(supplier::get);
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
                return resolved.get().decode(ops, input);
            }

            @Override
            public <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix) {
                return resolved.get().encode(input, ops, prefix);
            }
        };
    }
}
*///?}
