package za.co.neroland.neroeconomy.exchange;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One Savings Bond an account holds. Its interest is fixed when it is bought — and, for a real
 * bond, moved from the Reserve into the exchange account at that moment — so a later change of
 * the rate, or an empty Reserve, cannot touch it.
 *
 * @param id        the bond's own id
 * @param term      which of the three terms it is
 * @param principal what was put in, in whole credits
 * @param interest  what it pays on top at maturity, in whole credits; may be 0 for a tiny bond
 * @param boughtAt  economy time it was bought
 * @param maturesAt economy time from which it pays out: {@code boughtAt} plus the term
 */
public record Bond(UUID id, BondTerm term, long principal, long interest, long boughtAt, long maturesAt) {
    private record Row(String id, String term, long principal, long interest, long boughtAt, long maturesAt) { }

    private static final Codec<Row> ROW = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(Row::id),
            Codec.STRING.fieldOf("term").forGetter(Row::term),
            Codec.LONG.fieldOf("principal").forGetter(Row::principal),
            Codec.LONG.optionalFieldOf("interest", 0L).forGetter(Row::interest),
            Codec.LONG.optionalFieldOf("bought", 0L).forGetter(Row::boughtAt),
            Codec.LONG.fieldOf("matures").forGetter(Row::maturesAt)).apply(instance, Row::new));

    /** A row that is not a bond decodes to empty and is left out by whoever reads the list. */
    static final Codec<Optional<Bond>> CODEC = ROW.xmap(Bond::fromRow,
            bond -> bond.map(Bond::toRow).orElseGet(() -> new Row("", "", 0L, 0L, 0L, 0L)));

    public Bond {
        if (id == null || term == null || principal < 1L || interest < 0L)
            throw new IllegalArgumentException("bond");
    }

    /** Whether the bond pays out at {@code time}. */
    public boolean matured(long time) { return time >= maturesAt; }

    /** Everything the exchange account holds for this bond: the principal and the reserved interest. */
    public long held() { return ExchangeMath.add(principal, interest); }

    private Row toRow() { return new Row(id.toString(), term.key(), principal, interest, boughtAt, maturesAt); }

    private static Optional<Bond> fromRow(Row row) {
        try {
            BondTerm term = BondTerm.parse(row.term());
            if (term == null || row.principal() < 1L || row.interest() < 0L) return Optional.empty();
            return Optional.of(new Bond(UUID.fromString(row.id()), term, row.principal(), row.interest(),
                    row.boughtAt(), row.maturesAt()));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }
}
