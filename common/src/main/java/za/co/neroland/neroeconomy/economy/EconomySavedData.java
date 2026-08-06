package za.co.neroland.neroeconomy.economy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.nerolandcore.economy.Currency;
import za.co.neroland.nerolandcore.economy.CurrencyProvider;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.data.SavedDataRecovery;

/** Persistent, server-thread ledger keyed by abstract account and currency identifiers. */
public final class EconomySavedData extends SavedData implements CurrencyProvider {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "ledger");
    static final Codec<EconomySavedData> CODEC = codec();
    public static final SavedDataType<EconomySavedData> TYPE =
            new SavedDataType<>(ID, EconomySavedData::new, CODEC, null);
    private static final long MILLIS_PER_DAY = 86_400_000L;
    /** How far the audit list may run past its bound before an append pays for a purge. */
    private static final int PURGE_SLACK = 256;

    private record LedgerKey(AccountId account, String currency) { }

    private final Map<LedgerKey, Long> balances = new LinkedHashMap<>();
    private final Set<AccountId> initialized = new LinkedHashSet<>();
    private final Set<AccountId> loggingOptOut = new LinkedHashSet<>();
    /** Salted pseudonyms of erased accounts. Never a raw id, so the tombstone carries no identity. */
    private final Set<String> erased = new LinkedHashSet<>();
    private final List<TransactionRecord> transactions = new ArrayList<>();
    private String pseudonymSalt = UUID.randomUUID().toString();

    public EconomySavedData() { }

    public static EconomySavedData get(MinecraftServer server) {
        return SavedDataRecovery.get(server.overworld(), TYPE, EconomySavedData::new, ID.toString());
    }

    @Override public synchronized long getBalance(UUID player, Currency currency) {
        if (player == null) return 0L;
        return getBalance(new PlayerAccountId(player), currency);
    }

    /**
     * A read never opens an account. The starting balance is reported for an account that has not
     * been written to yet and is minted by the first write, so simply looking at a stranger's
     * balance cannot create ledger state or an audit row for them.
     */
    public synchronized long getBalance(AccountId account, Currency currency) {
        if (!valid(account, currency)) return 0L;
        Long balance = balances.get(key(account, currency));
        if (balance != null) return balance;
        return initialized.contains(account) || tombstoned(account) ? 0L : EconomyConfig.STARTING_BALANCE.get();
    }

    @Override public synchronized boolean deposit(UUID player, Currency currency, long amount) {
        return player != null && deposit(new PlayerAccountId(player), currency, amount, TransactionType.DEPOSIT);
    }

    public synchronized boolean deposit(UUID player, Currency currency, long amount, TransactionType type) {
        return player != null && deposit(new PlayerAccountId(player), currency, amount, type);
    }

    public synchronized boolean deposit(AccountId account, Currency currency, long amount, TransactionType type) {
        if (!valid(account, currency) || amount < 0L || type == null) return false;
        initialize(account, currency);
        if (amount == 0L) return true;
        LedgerKey key = key(account, currency);
        long current = balances.getOrDefault(key, 0L);
        if (Long.MAX_VALUE - current < amount) return false;
        balances.put(key, current + amount);
        append(type, amount, currency, null, account);
        setDirty();
        return true;
    }

    @Override public synchronized boolean withdraw(UUID player, Currency currency, long amount) {
        return player != null && withdraw(new PlayerAccountId(player), currency, amount, TransactionType.WITHDRAWAL);
    }

    public synchronized boolean withdraw(UUID player, Currency currency, long amount, TransactionType type) {
        return player != null && withdraw(new PlayerAccountId(player), currency, amount, type);
    }

    public synchronized boolean withdraw(AccountId account, Currency currency, long amount, TransactionType type) {
        if (!valid(account, currency) || amount < 0L || type == null) return false;
        initialize(account, currency);
        LedgerKey key = key(account, currency);
        long current = balances.getOrDefault(key, 0L);
        if (amount > current) return false;
        if (amount == 0L) return true;
        balances.put(key, current - amount);
        append(type, amount, currency, account, null);
        setDirty();
        return true;
    }

    @Override public synchronized boolean transfer(UUID from, UUID to, Currency currency, long amount) {
        return from != null && to != null && transfer(new PlayerAccountId(from), new PlayerAccountId(to),
                currency, amount, TransactionType.PLAYER_PAYMENT);
    }

    public synchronized boolean transfer(UUID from, UUID to, Currency currency, long amount, TransactionType type) {
        return from != null && to != null && transfer(new PlayerAccountId(from), new PlayerAccountId(to),
                currency, amount, type);
    }

    /** Validates and applies both balance changes under the same monitor. */
    public synchronized boolean transfer(AccountId from, AccountId to, Currency currency, long amount,
            TransactionType type) {
        if (!valid(from, currency) || !valid(to, currency) || amount < 0L || type == null) return false;
        initialize(from, currency);
        initialize(to, currency);
        LedgerKey sourceKey = key(from, currency);
        LedgerKey targetKey = key(to, currency);
        long source = balances.getOrDefault(sourceKey, 0L);
        if (from.equals(to)) return amount <= source;
        long target = balances.getOrDefault(targetKey, 0L);
        if (amount > source || Long.MAX_VALUE - target < amount) return false;
        if (amount == 0L) return true;
        balances.put(sourceKey, source - amount);
        balances.put(targetKey, target + amount);
        append(type, amount, currency, from, to);
        setDirty();
        return true;
    }

    public synchronized boolean setBalance(UUID player, long amount, TransactionType type) {
        if (player == null) return false;
        AccountId account = new PlayerAccountId(player);
        Currency currency = NeroEconomyCommon.CREDITS;
        if (amount < 0L || type == null) return false;
        initialize(account, currency);
        LedgerKey key = key(account, currency);
        long before = balances.getOrDefault(key, 0L);
        if (before == amount) return true;
        balances.put(key, amount);
        append(type, Math.abs(amount - before), currency, amount < before ? account : null,
                amount > before ? account : null);
        setDirty();
        return true;
    }

    public synchronized List<TransactionRecord> history(UUID player, int limit) {
        if (player == null || limit <= 0) return List.of();
        AccountId account = new PlayerAccountId(player);
        // Reading history is not a write: expired rows are filtered out, and the sweep deletes them.
        int days = EconomyConfig.TRANSACTION_RETENTION_DAYS.get();
        long cutoff = days <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() - days * MILLIS_PER_DAY;
        String ref = reference(account);
        return transactions.stream().filter(row -> row.timestamp() >= cutoff)
                .filter(row -> ref.equals(row.sourceAccount()) || ref.equals(row.destinationAccount()))
                .sorted(Comparator.comparingLong(TransactionRecord::timestamp).reversed())
                .limit(Math.min(limit, EconomyConfig.HISTORY_LIMIT_PER_PLAYER.get())).toList();
    }

    public synchronized boolean loggingOptOut(UUID player) {
        return player != null && loggingOptOut.contains(new PlayerAccountId(player));
    }

    public synchronized void setLoggingOptOut(UUID player, boolean optOut) {
        if (player == null) return;
        AccountId account = new PlayerAccountId(player);
        boolean changed = optOut ? loggingOptOut.add(account) : loggingOptOut.remove(account);
        if (changed) setDirty();
    }

    public synchronized PlayerLedgerExport export(UUID player) {
        if (player == null) throw new IllegalArgumentException("player");
        AccountId account = new PlayerAccountId(player);
        long balance = getBalance(account, NeroEconomyCommon.CREDITS);
        return new PlayerLedgerExport(player, balance, NeroEconomyCommon.CREDITS.id().toString(),
                loggingOptOut.contains(account), history(player, EconomyConfig.HISTORY_LIMIT_PER_PLAYER.get()));
    }

    /**
     * Erasure also leaves a salted-pseudonym tombstone. Without it the very next balance read would
     * re-open the account and mint the starting balance again, quietly undoing the erasure.
     */
    @Override public synchronized void forgetPlayer(UUID player) {
        if (player == null) return;
        AccountId account = new PlayerAccountId(player);
        String ref = reference(account);
        boolean changed = balances.keySet().removeIf(key -> key.account().equals(account));
        changed |= initialized.remove(account);
        changed |= loggingOptOut.remove(account);
        changed |= transactions.removeIf(row -> ref.equals(row.sourceAccount())
                || ref.equals(row.destinationAccount()));
        changed |= erased.add(ref);
        if (changed) setDirty();
    }

    /** Load-time and daily purge. */
    public synchronized boolean purgeExpired(long now) {
        int before = transactions.size();
        int days = EconomyConfig.TRANSACTION_RETENTION_DAYS.get();
        long cutoff = days <= 0 ? Long.MAX_VALUE : now - days * MILLIS_PER_DAY;
        transactions.removeIf(row -> row.timestamp() < cutoff);
        int max = EconomyConfig.MAX_TRANSACTION_RECORDS.get();
        if (transactions.size() > max) transactions.subList(0, transactions.size() - max).clear();
        if (before != transactions.size()) setDirty();
        return before != transactions.size();
    }

    /** Called from every write path; an erased account re-opens empty rather than freshly funded. */
    private void initialize(AccountId account, Currency currency) {
        if (!initialized.add(account)) return;
        long starting = tombstoned(account) ? 0L : EconomyConfig.STARTING_BALANCE.get();
        balances.put(key(account, currency), starting);
        if (starting > 0L) append(TransactionType.STARTING_BALANCE, starting, currency, null, account);
        setDirty();
    }

    private boolean tombstoned(AccountId account) {
        return !erased.isEmpty() && erased.contains(reference(account));
    }

    private boolean valid(AccountId account, Currency currency) {
        return account != null && currency != null && NeroEconomyCommon.CREDITS.equals(currency)
                && account.kind() != null && !account.kind().isBlank()
                && account.value() != null && !account.value().isBlank();
    }

    private static LedgerKey key(AccountId account, Currency currency) {
        return new LedgerKey(account, currency.id().toString());
    }

    /**
     * A two-party row survives one party opting out: only the opted-out side's pseudonym is blanked,
     * so the other party keeps a complete history. Retention is swept on load and daily; an append
     * only pays for a purge once the list has run past its bound.
     */
    private void append(TransactionType type, long amount, Currency currency, AccountId source, AccountId target) {
        int max = EconomyConfig.MAX_TRANSACTION_RECORDS.get();
        if (amount == 0L || max == 0 || EconomyConfig.TRANSACTION_RETENTION_DAYS.get() == 0) return;
        long now = System.currentTimeMillis();
        transactions.add(new TransactionRecord(type, amount, currency.id().toString(), now,
                pseudonym(source), pseudonym(target)));
        if (transactions.size() > max + PURGE_SLACK) purgeExpired(now);
    }

    private String pseudonym(AccountId account) {
        return account == null || loggingOptOut.contains(account) ? "" : reference(account);
    }

    private String reference(AccountId account) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((pseudonymSalt + ':' + account.stableKey()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    void importRecord(TransactionRecord record) { transactions.add(record); }
    void pruneForTest(long now) { purgeExpired(now); }
    int transactionCount() { return transactions.size(); }
    boolean tombstoned(UUID player) { return tombstoned(new PlayerAccountId(player)); }

    private record BalanceRow(String accountKind, String account, String currency, long balance) {
        static final Codec<BalanceRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("account_kind", "player").forGetter(BalanceRow::accountKind),
                Codec.STRING.fieldOf("player").forGetter(BalanceRow::account),
                Codec.STRING.optionalFieldOf("currency", NeroEconomyCommon.CREDITS.id().toString())
                        .forGetter(BalanceRow::currency),
                Codec.LONG.fieldOf("balance").forGetter(BalanceRow::balance)).apply(instance, BalanceRow::new));
    }

    private record AuditRow(String type, long amount, String currency, long timestamp, String source,
            String destination) {
        static final Codec<AuditRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("type").forGetter(AuditRow::type),
                Codec.LONG.fieldOf("amount").forGetter(AuditRow::amount),
                Codec.STRING.fieldOf("currency").forGetter(AuditRow::currency),
                Codec.LONG.fieldOf("timestamp").forGetter(AuditRow::timestamp),
                Codec.STRING.optionalFieldOf("source", "").forGetter(AuditRow::source),
                Codec.STRING.optionalFieldOf("destination", "").forGetter(AuditRow::destination))
                .apply(instance, AuditRow::new));
    }

    private static Codec<EconomySavedData> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                BalanceRow.CODEC.listOf().optionalFieldOf("balances", List.of()).forGetter(EconomySavedData::balanceRows),
                Codec.STRING.listOf().optionalFieldOf("initialized", List.of()).forGetter(EconomySavedData::initializedRows),
                Codec.STRING.listOf().optionalFieldOf("logging_opt_out", List.of()).forGetter(EconomySavedData::optOutRows),
                AuditRow.CODEC.listOf().optionalFieldOf("transactions", List.of()).forGetter(EconomySavedData::auditRows),
                Codec.STRING.optionalFieldOf("pseudonym_salt", "").forGetter(data -> data.pseudonymSalt),
                Codec.STRING.listOf().optionalFieldOf("erased", List.of()).forGetter(EconomySavedData::erasedRows))
                .apply(instance, EconomySavedData::fromData));
    }

    private List<BalanceRow> balanceRows() {
        return balances.entrySet().stream().map(entry -> new BalanceRow(entry.getKey().account().kind(),
                entry.getKey().account().value(), entry.getKey().currency(), entry.getValue())).toList();
    }
    private List<String> initializedRows() { return initialized.stream().map(AccountId::stableKey).toList(); }
    private List<String> optOutRows() { return loggingOptOut.stream().map(AccountId::stableKey).toList(); }
    private List<String> erasedRows() { return List.copyOf(erased); }
    private List<AuditRow> auditRows() {
        return transactions.stream().map(row -> new AuditRow(row.type().name(), row.amount(), row.currency(),
                row.timestamp(), row.sourceAccount(), row.destinationAccount())).toList();
    }

    private static EconomySavedData fromData(List<BalanceRow> balances, List<String> initialized,
            List<String> optOut, List<AuditRow> transactions, String salt, List<String> erased) {
        EconomySavedData data = new EconomySavedData();
        if (!salt.isBlank()) data.pseudonymSalt = salt;
        // Tombstones are stored already-hashed under this salt, so they are read back verbatim.
        erased.stream().filter(value -> !value.isBlank()).forEach(data.erased::add);
        for (BalanceRow row : balances) {
            AccountId account = parseAccount(row.accountKind(), row.account());
            if (account != null && row.balance() >= 0L && row.currency().equals(NeroEconomyCommon.CREDITS.id().toString()))
                data.balances.put(new LedgerKey(account, row.currency()), row.balance());
        }
        initialized.stream().map(EconomySavedData::parseStableAccount).filter(java.util.Objects::nonNull)
                .forEach(data.initialized::add);
        optOut.stream().map(EconomySavedData::parseStableAccount).filter(java.util.Objects::nonNull)
                .forEach(data.loggingOptOut::add);
        for (AuditRow row : transactions) try {
            TransactionType type = TransactionType.valueOf(row.type());
            if (row.amount() >= 0L) data.transactions.add(new TransactionRecord(type, row.amount(), row.currency(),
                    row.timestamp(), row.source(), row.destination()));
        } catch (IllegalArgumentException ignored) { }
        data.purgeExpired(System.currentTimeMillis());
        return data;
    }

    private static AccountId parseStableAccount(String value) {
        int separator = value.indexOf(':');
        return separator < 1 ? parseAccount("player", value)
                : parseAccount(value.substring(0, separator), value.substring(separator + 1));
    }

    private static AccountId parseAccount(String kind, String value) {
        if (!"player".equals(kind)) return null;
        try { return new PlayerAccountId(UUID.fromString(value)); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
