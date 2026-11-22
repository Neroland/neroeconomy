package za.co.neroland.neroeconomy.telemetry;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import io.sentry.IScopes;
import io.sentry.Scope;
import io.sentry.Scopes;
import io.sentry.SentryClient;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.UncaughtExceptionHandlerIntegration;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;

import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.platform.Services;

/**
 * Opt-out, PII-free NeroEconomy-only Sentry reporting.
 *
 * <p>Uses its own isolated client and scopes and never the static {@code Sentry} API: every Nero mod (and
 * any other mod) embeds the same un-relocated Sentry jar, so a global {@code Sentry.init} would replace the
 * previous mod's hub and its package filter would drop everyone else's events.</p>
 */
public final class NeroEconomyTelemetry {
    static final String PLACEHOLDER_DSN = "https://REPLACE-ME@sentry.invalid/0";
    private static final String DSN =
            "https://535ff9d6800f491194551d7dc5e23d64@o4511183823241216.ingest.de.sentry.io/4512233482092624";
    private static final String PACKAGE = "za.co.neroland.neroeconomy";
    public static final int MAX_EVENTS_PER_SESSION = 10;
    private static final Pattern USER_PATH = Pattern.compile(
            "(?i)(?:[A-Z]:)?[/\\\\](?:Users|home)[/\\\\][^/\\\\\\s:;,'\"]+");
    /** Dashed or bare 32-digit hex: a player id must never leave the server inside an error message. */
    private static final Pattern UUID_SHAPE = Pattern.compile(
            "(?i)(?<![0-9a-f])(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{32})(?![0-9a-f])");
    private static final AtomicInteger SENT = new AtomicInteger();
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static volatile boolean active;
    /** NeroEconomy's own scopes; null until {@link #init()} succeeds. */
    private static volatile IScopes scopes;
    /** Process lifetime, never closed (a field so ecj does not flag a resource leak). */
    private static UncaughtExceptionHandlerIntegration uncaughtHandler;

    private NeroEconomyTelemetry() { }

    public static synchronized void init() {
        if (active || PLACEHOLDER_DSN.equals(DSN) || !EconomyConfig.TELEMETRY_ENABLED.get()) {
            return;
        }
        SentryOptions options = baseOptions(Services.PLATFORM.getModVersion(),
                Services.PLATFORM.isDevelopmentEnvironment() ? "development" : "production");
        options.setBeforeSend((event, hint) -> ForeignMixinFailure.matches(event) ? null : filter(event));
        IScopes own = isolatedScopes(options, "neroeconomy");
        scopes = own;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                own.flush(2000L);
            } catch (RuntimeException ignored) {
                // Shutting down; nothing more to do.
            }
        }, "neroeconomy-sentry-shutdown"));
        active = true;
    }

    /** The options every NeroEconomy client starts from; package-private for the isolation test. */
    static SentryOptions baseOptions(String modVersion, String environment) {
        SentryOptions options = new SentryOptions();
        options.setDsn(DSN);
        options.setRelease("neroeconomy@" + modVersion);
        options.setEnvironment(environment);
        options.setSendDefaultPii(false);
        options.setAttachServerName(false);
        options.setEnableUncaughtExceptionHandler(true);
        options.setEnableShutdownHook(false);
        options.setEnableAutoSessionTracking(false);
        return options;
    }

    /**
     * Activates {@code options} (what {@code Sentry.init} would otherwise do), binds a client to a fresh
     * set of scopes and chains the uncaught-exception handler to the previous one. Never touches the
     * global hub.
     */
    static IScopes isolatedScopes(SentryOptions options, String creator) {
        options.activate();
        Scopes own = new Scopes(new Scope(options), new Scope(options), new Scope(options), creator);
        own.bindClient(new SentryClient(options));
        if (options.isEnableUncaughtExceptionHandler()) {
            uncaughtHandler = new UncaughtExceptionHandlerIntegration();
            uncaughtHandler.register(own, options);
        }
        return own;
    }

    public static void captureHandledException(Throwable failure, String operation) {
        IScopes s = scopes;
        if (!active || s == null || failure == null || !touchesEconomy(failure)) return;
        // Tags set inside withScope never reach the event in Sentry 8.x: use the ScopeCallback overload.
        s.captureException(failure, scope -> {
            scope.setTag("handled", "true");
            scope.setTag("operation", scrub(operation));
        });
    }

    private static SentryEvent filter(SentryEvent event) {
        if (!related(event)) return null;
        String fingerprint = fingerprint(event);
        if (!SEEN.add(fingerprint) || SENT.incrementAndGet() > MAX_EVENTS_PER_SESSION) return null;
        event.setUser(null);
        event.setServerName(null);
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) for (SentryException exception : exceptions) {
            if (exception.getValue() != null) exception.setValue(scrub(exception.getValue()));
            SentryStackTrace trace = exception.getStacktrace();
            if (trace != null && trace.getFrames() != null) for (SentryStackFrame frame : trace.getFrames()) {
                frame.setAbsPath(null);
            }
        }
        Message message = event.getMessage();
        if (message != null && message.getFormatted() != null) message.setFormatted(scrub(message.getFormatted()));
        return event;
    }

    private static boolean related(SentryEvent event) {
        if (touchesEconomy(event.getThrowable())) return true;
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) for (SentryException exception : exceptions) {
            SentryStackTrace trace = exception.getStacktrace();
            if (trace != null && trace.getFrames() != null && trace.getFrames().stream()
                    .anyMatch(frame -> frame.getModule() != null && frame.getModule().startsWith(PACKAGE))) return true;
        }
        return false;
    }

    private static boolean touchesEconomy(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            for (StackTraceElement frame : failure.getStackTrace()) {
                if (frame.getClassName().startsWith(PACKAGE)) return true;
            }
        }
        return false;
    }

    private static String fingerprint(SentryEvent event) {
        Throwable failure = event.getThrowable();
        if (failure == null) return "event:" + event.getEventId();
        StringBuilder result = new StringBuilder(failure.getClass().getName());
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().startsWith(PACKAGE)) result.append('|').append(frame.getClassName())
                    .append(':').append(frame.getLineNumber());
        }
        return result.toString();
    }

    /** Removes home-directory paths and anything shaped like a UUID from text bound for telemetry. */
    static String scrub(String value) {
        if (value == null) return "";
        return UUID_SHAPE.matcher(USER_PATH.matcher(value).replaceAll("/~")).replaceAll("<uuid>");
    }
}
