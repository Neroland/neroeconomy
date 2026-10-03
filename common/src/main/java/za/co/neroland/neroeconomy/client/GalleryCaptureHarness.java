package za.co.neroland.neroeconomy.client;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import za.co.neroland.neroeconomy.platform.Services;

/**
 * CLIENT, creative-debug, <b>development environment only</b> (see {@link #available()}): the
 * {@code /necgallery} command and the tick hook are wired up in a dev run and nowhere else, and the
 * {@code neroeconomy:capture} dimension it teleports into is left out of every built jar, so a
 * release build carries no capture command and adds no dimension to anyone's world.
 *
 * <p>An automated, fully-reproducible screenshot pass over the
 * {@code /neroeconomy gallery} scene — the four listing shots the Modrinth gallery sync consumes
 * ({@code bank_terminal}, {@code player_shop}, {@code trading_terminal}, {@code market_board}).
 * {@code /neroeconomy gallery} builds the scene server-side; this harness then drives the camera,
 * hides every overlay and writes a PNG per shot via {@link Screenshot#grab}, so the gallery can be
 * re-rendered identically after texture/model changes instead of hand-framing every shot.
 *
 * <p><b>Cross-loader.</b> This is loader-agnostic: it only touches vanilla client classes. Each loader
 * wires it from its own client setup — {@link #registerClientCommands(CommandDispatcher)} from the
 * loader's client-command hook (NeoForge/Forge {@code RegisterClientCommandsEvent}, Fabric
 * {@code ClientCommandRegistrationCallback}) and {@link #tick()} from the loader's per-client-tick
 * hook. The command bodies never read the command source, so the registration is generic in the
 * brigadier source type {@code <S>} and the same call serves all three loaders.
 *
 * <p><b>Commands</b> (client-side — they never reach the server dispatcher, which owns
 * {@code /neroeconomy gallery}). The root is {@code /necgallery}, NOT {@code /neroeconomy}: a
 * client-dispatcher root that shares its name with a server command SHADOWS the server subtree — the
 * client parser matches the root, fails on the server-only child ("Incorrect argument for command")
 * and never forwards to the server. Keep the roots distinct.
 * <ul>
 *   <li>{@code /necgallery capture [time]} — the gallery pass: teleports into the flat
 *       {@code neroeconomy:capture} dimension at a fixed origin (identical backdrop every run),
 *       rebuilds the gallery there from scratch ({@code gallery clear} then {@code gallery} — so a
 *       rerun never leaves lingering blocks, entities or stale market listings), freezes
 *       time/weather/clouds, then shoots the four framed cluster shots.</li>
 *   <li>{@code /necgallery capture shot <name>} — capture the CURRENT view once (overlays hidden) to
 *       {@code neroeconomy/<name>.png}. Use this to grab a hand-framed money shot.</li>
 * </ul>
 *
 * <p>{@code time} accepts {@code day}/{@code noon}/{@code night}/{@code midnight} or a tick number.
 * The capture dimension is overworld-type, so it has a clock and accepts {@code time set}.
 *
 * <p><b>Coordinate contract with the server-side builder.</b> {@code EconomyCommands.buildGallery}
 * lays four clusters out on separate compass bearings, {@link #CLUSTER_RADIUS} blocks from the
 * gallery origin, each with its own backdrop wall on the far side so no other cluster is ever in
 * frame. The camera poses below mirror those bearings exactly — <b>tune the two together</b>:
 *
 * <pre>
 *   shot               bearing  cluster centre          backdrop      camera                       target
 *   bank_terminal      NORTH    (ox,   oy+1, oz-24)     z = oz-26     (ox+2.5, oy+2.6, oz-19.5)    (ox,    oy+1.6, oz-24.5)
 *   player_shop        EAST     (ox+24,oy+1, oz)        x = ox+26     (ox+21,  oy+2.4, oz-5.5)     (ox+24, oy+1.6, oz+0.5)
 *   trading_terminal   SOUTH    (ox,   oy+2, oz+24)     z = oz+26     (ox-2.5, oy+3.8, oz+19.5)    (ox,    oy+2.6, oz+24.5)
 *   market_board       WEST     (ox-24,oy+1, oz)        x = ox-26     (ox-19.5,oy+2.6, oz+3)       (ox-24.5,oy+1.7, oz-0.5)
 * </pre>
 *
 * <p>{@code oy} is the gallery FLOOR level: the harness stands (and therefore builds) at
 * {@link #ORIGIN_Y}, and the builder lays its pads at exactly that y with the display blocks one
 * above. The capture dimension's smooth-stone surface tops out at y=63, so y=64 is the first air
 * layer — the pads replace it.
 *
 * <p>Outputs land in {@code .minecraft/screenshots/neroeconomy/<shot>.png} at the game window's
 * native resolution with the HUD, clouds and view-bob all suppressed for the duration of the run.
 * Since native res can be 2.5k+ wide / 5-8 MB, run {@code ./gradlew compressScreenshots} afterwards
 * to cap each shot at 1920 px / &lt;4 MB before committing.
 */
public final class GalleryCaptureHarness {

    /** Ticks to let chunks/entities settle and a fresh frame render before grabbing. */
    private static final int SETTLE_TICKS = 12;

    /** Ticks to wait after teleporting + triggering the build for the scene to load and sync. */
    private static final int BUILD_WARMUP_TICKS = 120;

    /**
     * Ticks to wait AFTER the build/kill commands before the cleanup pass. Lets entity-kill death
     * animations (smoke puffs) finish and any dropped items settle onto the ground, so the cleanup
     * sweep actually catches them and they're gone well before the shot.
     */
    private static final int CLEANUP_TICKS = 60;

    /** Deterministic flat backdrop (data/neroeconomy/dimension/capture.json) + the fixed build origin. */
    private static final String CAPTURE_DIMENSION = "neroeconomy:capture";
    private static final int ORIGIN_X = 0;
    private static final int ORIGIN_Y = 64;
    private static final int ORIGIN_Z = 0;

    /**
     * Distance from the gallery origin to each cluster centre. MUST match
     * {@code EconomyCommands.GALLERY_RADIUS} — the two sides of the coordinate contract.
     */
    private static final int CLUSTER_RADIUS = 24;

    /**
     * One framed capture. {@code setup} = server commands run pre-warmup (teleport into the dimension,
     * pin time/weather); {@code build} = server commands run AFTER warmup, once chunks are loaded
     * (clear, place blocks, kill old entities); {@code cleanup} = server commands run after a further
     * {@link #CLEANUP_TICKS} pause (sweep up dropped items / dead-entity litter so the ground is clean
     * before the shot); {@code warmup} = ticks to wait after setup for the scene to load/sync;
     * {@code camera}/{@code target} = the pose ({@code null} poses → "keep current view").
     */
    private record Shot(String name, List<String> setup, List<String> build, List<String> cleanup,
            int warmup, Vec3 camera, Vec3 target) {
    }

    private enum Phase { MOVE, WARMUP, CLEANUP, SETTLE, SHOOT }

    private static final Deque<Shot> QUEUE = new ArrayDeque<>();
    private static boolean running;
    private static boolean hudWasHidden;
    private static boolean bobViewWas;
    private static boolean flyingWas;
    private static CloudStatus cloudsWere;
    private static Phase phase = Phase.MOVE;
    private static int warmup;
    private static int cleanupWait;
    private static int settle;
    private static Shot current;

    // The HUD-hide flag and the main render target both DIVERGE across 26.1.2 ↔ 26.2, so they're
    // resolved reflectively (no compile-time reference to version-specific symbols). 26.1.x:
    // Options#hideGui (boolean) + Minecraft#getMainRenderTarget(). 26.2: Gui#hud →
    // Hud#isHidden()/toggle() + GameRenderer#mainRenderTarget().
    private static boolean hudResolved;
    private static java.lang.reflect.Field optHideGuiField;     // 26.1.x
    private static java.lang.reflect.Field guiHudField;         // 26.2
    private static java.lang.reflect.Method hudIsHiddenMethod;  // 26.2
    private static java.lang.reflect.Method hudToggleMethod;    // 26.2
    private static boolean rtResolved;
    private static java.lang.reflect.Method mcGetRenderTarget;  // 26.1.x
    private static java.lang.reflect.Method grMainRenderTarget; // 26.2

    private GalleryCaptureHarness() {
    }

    /**
     * Whether the capture tooling exists in this run: only in a development environment. Each
     * loader's client setup asks this before registering the command or the tick hook, and
     * {@link #registerClientCommands} asks again, so a release jar has no {@code /necgallery}.
     */
    public static boolean available() {
        return Services.PLATFORM.isDevelopmentEnvironment();
    }

    // ------------------------------------------------------------------------------------------------
    // Command registration (generic in the brigadier source type — bodies never read the source).
    // ------------------------------------------------------------------------------------------------

    /**
     * Register the client-side {@code /necgallery capture …} tree on the given dispatcher. Generic in
     * the source type {@code S} so the SAME method serves NeoForge/Forge ({@code CommandSourceStack})
     * and Fabric ({@code FabricClientCommandSource}); the command bodies drive the local client only.
     * The root must NOT be {@code neroeconomy} — see the class javadoc (it would shadow the server tree).
     */
    public static <S> void registerClientCommands(CommandDispatcher<S> dispatcher) {
        if (!available()) {
            return;
        }
        dispatcher.register(
                LiteralArgumentBuilder.<S>literal("necgallery")
                        .then(LiteralArgumentBuilder.<S>literal("capture")
                                .executes(ctx -> startCapture("noon"))
                                .then(LiteralArgumentBuilder.<S>literal("shot")
                                        .then(RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.word())
                                                .executes(ctx -> shotHere(StringArgumentType.getString(ctx, "name")))))
                                .then(RequiredArgumentBuilder.<S, String>argument("time", StringArgumentType.word())
                                        .executes(ctx -> startCapture(StringArgumentType.getString(ctx, "time"))))));
    }

    // ------------------------------------------------------------------------------------------------
    // Run starters.
    // ------------------------------------------------------------------------------------------------

    private static int startCapture(String time) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return 0;
        }
        if (running) {
            mc.player.sendSystemMessage(Component.translatable("command.neroeconomy.capture.already_running"));
            return 0;
        }
        QUEUE.clear();
        QUEUE.addAll(galleryQueue(time));
        begin(mc);
        mc.player.sendSystemMessage(Component.translatable("command.neroeconomy.capture.started", time,
                QUEUE.size()));
        return Command.SINGLE_SUCCESS;
    }

    private static int shotHere(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || running) {
            return 0;
        }
        QUEUE.clear();
        QUEUE.add(new Shot(name, List.of(), List.of(), List.of(), 0, null, null)); // grab the current view as-is
        begin(mc);
        return Command.SINGLE_SUCCESS;
    }

    /** The gallery shot list with the one-time rebuild + environment-pin setup attached to shot 0. */
    private static List<Shot> galleryQueue(String time) {
        List<Shot> shots = new ArrayList<>(buildShots(ORIGIN_X, ORIGIN_Y, ORIGIN_Z));
        // Shot 0 carries the one-time scene rebuild. SETUP (pre-warmup) only teleports into the flat
        // capture dimension and pins the environment — NO building yet. After the warmup (chunks loaded),
        // BUILD rebuilds the gallery FROM SCRATCH (clear → build, so reruns never stack blocks or leave
        // ghost market listings behind). Doing this post-warmup avoids placing into unloaded chunks.
        // CLEANUP (after a further pause) sweeps the ground. These are server commands (they need the
        // creative world's cheats).
        List<String> setup = List.of(
                "execute in " + CAPTURE_DIMENSION + " run tp @s "
                        + (ORIGIN_X + 0.5) + " " + ORIGIN_Y + " " + (ORIGIN_Z + 0.5),
                "gamerule advance_time false",    // 26.1 renamed doDaylightCycle → advance_time
                "gamerule advance_weather false", // …and doWeatherCycle → advance_weather
                "time set " + time,               // capture dim is overworld-type → has a clock
                "weather clear");
        List<String> build = List.of(
                "neroeconomy gallery clear", // wipe any prior footprint so the rebuild can't stack duplicates
                "neroeconomy gallery",       // fresh build → fresh listings, no lingering artifacts
                // Nothing living is ever spawned, so this is only litter control (a rerun's cleared
                // blocks can drop stacks if a prior run left a non-air block behind).
                "kill @e[type=item]",
                "kill @e[type=experience_orb]");
        Shot first = shots.get(0);
        shots.set(0, new Shot(first.name(), setup, build, groundClear(), BUILD_WARMUP_TICKS,
                first.camera(), first.target()));
        return shots;
    }

    private static void begin(Minecraft mc) {
        // screenshots/ is created by Screenshot.grab; the neroeconomy/ subfolder is not, so make it.
        new File(mc.gameDirectory, "screenshots/neroeconomy").mkdirs();
        hudWasHidden = isHudHidden(mc);
        setHudHidden(mc, true);
        cloudsWere = mc.options.cloudStatus().get();
        mc.options.cloudStatus().set(CloudStatus.OFF); // clouds scroll with game time → freeze them out of frame
        bobViewWas = mc.options.bobView().get();
        mc.options.bobView().set(false); // kill view-bob so a pinned pose renders the exact same frame
        // Fly the player so they hover at the teleport spot instead of falling through the (not-yet-built)
        // scene while we wait — keeps the right chunks loaded and the camera where we put it.
        flyingWas = mc.player != null && mc.player.getAbilities().flying;
        setFlying(mc, true);
        running = true;
        phase = Phase.MOVE; // each shot's setup carries any teleport/build; warmup is per-shot
        current = null;
    }

    /** Toggle creative flight so the camera hovers (no gravity drift) for the duration of a run. */
    private static void setFlying(Minecraft mc, boolean fly) {
        if (mc.player == null) {
            return;
        }
        mc.player.getAbilities().flying = fly && mc.player.getAbilities().mayfly;
        mc.player.onUpdateAbilities();
    }

    // ------------------------------------------------------------------------------------------------
    // Per-client-tick state machine (driven from each loader's client-tick hook).
    // ------------------------------------------------------------------------------------------------

    /** Called once per client tick from each loader's own client-tick hook. No-op unless a run is live. */
    public static void tick() {
        if (!running) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            finish(mc);
            return;
        }

        switch (phase) {
            case MOVE -> {
                final Shot shot = QUEUE.poll();
                current = shot;
                if (shot == null) {
                    finish(mc);
                    return;
                }
                for (String cmd : shot.setup()) { // pre-warmup: teleport into the dimension + pin time/weather
                    player.connection.sendCommand(cmd);
                }
                warmup = shot.warmup();
                phase = Phase.WARMUP;
            }
            case WARMUP -> {
                final Shot shot = current;
                if (shot == null) {
                    phase = Phase.MOVE;
                    return;
                }
                holdStill(mc, player); // keep flying + motionless so the player doesn't fall during the wait
                if (--warmup <= 0) { // teleport done + chunks now loaded → safe to clear / build / kill
                    for (String cmd : shot.build()) { // block placement needs loaded chunks (else "not loaded")
                        player.connection.sendCommand(cmd);
                    }
                    // Pause so any kill death-animations finish + dropped items settle before the sweep.
                    cleanupWait = shot.cleanup().isEmpty() ? 0 : CLEANUP_TICKS;
                    phase = Phase.CLEANUP;
                }
            }
            case CLEANUP -> {
                final Shot shot = current;
                if (shot == null) {
                    phase = Phase.MOVE;
                    return;
                }
                holdStill(mc, player); // stay aloft while the litter sweep runs
                if (--cleanupWait <= 0) {
                    for (String cmd : shot.cleanup()) { // sweep dropped items / orb litter off the ground
                        player.connection.sendCommand(cmd);
                    }
                    applyPose(player, shot);
                    settle = SETTLE_TICKS;
                    phase = Phase.SETTLE;
                }
            }
            case SETTLE -> {
                final Shot shot = current;
                if (shot == null) {
                    phase = Phase.MOVE;
                    return;
                }
                applyPose(player, shot); // re-pin every tick so gravity can't drift the camera
                if (--settle <= 0) {
                    phase = Phase.SHOOT;
                }
            }
            case SHOOT -> {
                final Shot shot = current;
                if (shot == null) {
                    phase = Phase.MOVE;
                    return;
                }
                grab(mc, shot.name());
                phase = Phase.MOVE;
            }
        }
    }

    /** Keep the player flying + motionless during a wait so gravity doesn't drift the camera. */
    private static void holdStill(Minecraft mc, LocalPlayer player) {
        if (!player.getAbilities().flying && player.getAbilities().mayfly) {
            setFlying(mc, true); // re-assert if the server toggled it off
        }
        player.setDeltaMovement(Vec3.ZERO);
    }

    /** Snap the player (the render camera) to the shot's pose, holding it still. */
    private static void applyPose(LocalPlayer player, Shot shot) {
        if (shot == null) {
            return;
        }
        Vec3 cam = shot.camera();
        Vec3 tgt = shot.target();
        if (cam == null || tgt == null) {
            return; // "keep current view" shot
        }
        double dx = tgt.x - cam.x;
        double dy = tgt.y - cam.y;
        double dz = tgt.z - cam.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        player.snapTo(cam.x, cam.y, cam.z, yaw, pitch);
        player.setDeltaMovement(Vec3.ZERO);
    }

    private static void grab(Minecraft mc, String name) {
        RenderTarget target = mainRenderTarget(mc);
        if (target == null) {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.translatable("command.neroeconomy.capture.no_render_target"));
            }
            return;
        }
        Screenshot.grab(mc.gameDirectory, "neroeconomy/" + name + ".png", target, 1,
                msg -> {
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(msg);
                    }
                });
    }

    private static void finish(Minecraft mc) {
        setHudHidden(mc, hudWasHidden);
        if (cloudsWere != null) {
            mc.options.cloudStatus().set(cloudsWere);
        }
        mc.options.bobView().set(bobViewWas);
        setFlying(mc, flyingWas); // restore the player's flight state
        running = false;
        current = null;
        QUEUE.clear();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.translatable("command.neroeconomy.capture.done"));
        }
    }

    // --- HUD hide + render target (version-divergent → reflective) ----------------------------------

    private static void resolveHud() {
        if (hudResolved) {
            return;
        }
        hudResolved = true;
        try { // 26.1.x: Options#hideGui boolean field
            optHideGuiField = net.minecraft.client.Options.class.getDeclaredField("hideGui");
            optHideGuiField.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            optHideGuiField = null;
        }
        if (optHideGuiField == null) {
            try { // 26.2: Gui#hud → Hud#isHidden()/toggle()
                guiHudField = net.minecraft.client.gui.Gui.class.getDeclaredField("hud");
                guiHudField.setAccessible(true);
                Class<?> hudType = guiHudField.getType();
                hudIsHiddenMethod = hudType.getMethod("isHidden");
                hudToggleMethod = hudType.getMethod("toggle");
            } catch (ReflectiveOperationException | RuntimeException e) {
                guiHudField = null;
                hudIsHiddenMethod = null;
                hudToggleMethod = null;
            }
        }
    }

    private static boolean isHudHidden(Minecraft mc) {
        resolveHud();
        try {
            if (optHideGuiField != null) {
                return optHideGuiField.getBoolean(mc.options);
            }
            if (guiHudField != null && hudIsHiddenMethod != null) {
                Object hud = guiHudField.get(mc.gui);
                return hud != null && Boolean.TRUE.equals(hudIsHiddenMethod.invoke(hud));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fall through to "not hidden"
        }
        return false;
    }

    private static void setHudHidden(Minecraft mc, boolean hidden) {
        resolveHud();
        try {
            if (optHideGuiField != null) {
                optHideGuiField.setBoolean(mc.options, hidden);
                return;
            }
            if (guiHudField != null && hudIsHiddenMethod != null && hudToggleMethod != null) {
                Object hud = guiHudField.get(mc.gui);
                if (hud != null && !Boolean.valueOf(hidden).equals(hudIsHiddenMethod.invoke(hud))) {
                    hudToggleMethod.invoke(hud); // toggle only when not already in the wanted state
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // best-effort; a missing flag just means the HUD shows in the shot
        }
    }

    /** The main framebuffer: 26.1.x {@code Minecraft#getMainRenderTarget()}, 26.2 {@code GameRenderer#mainRenderTarget()}. */
    private static RenderTarget mainRenderTarget(Minecraft mc) {
        if (!rtResolved) {
            rtResolved = true;
            try {
                mcGetRenderTarget = Minecraft.class.getMethod("getMainRenderTarget");
            } catch (NoSuchMethodException | RuntimeException e) {
                mcGetRenderTarget = null;
            }
            if (mcGetRenderTarget == null) {
                try {
                    grMainRenderTarget = mc.gameRenderer.getClass().getMethod("mainRenderTarget");
                } catch (ReflectiveOperationException | RuntimeException e) {
                    grMainRenderTarget = null;
                }
            }
        }
        try {
            if (mcGetRenderTarget != null) {
                return (RenderTarget) mcGetRenderTarget.invoke(mc);
            }
            if (grMainRenderTarget != null) {
                return (RenderTarget) grMainRenderTarget.invoke(mc.gameRenderer);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fall through
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------
    // Gallery shot list. The names here are the CONTRACT with .github/workflows/modrinth-gallery.yml
    // (its ORDER/TITLE/DESC maps are keyed on exactly these four) and the coordinates are the contract
    // with EconomyCommands.buildGallery — see the table in the class javadoc. Tune the two together.
    // ------------------------------------------------------------------------------------------------

    private static List<Shot> buildShots(int ox, int oy, int oz) {
        List<Shot> shots = new ArrayList<>();
        int r = CLUSTER_RADIUS;

        // Framing principles: get CLOSE so the subject fills the frame; aim at the subject's MID-height
        // so the horizon sits high and there is little empty sky/floor; shoot rows DOWN THE LINE from
        // near one end at near-eye height so they recede with perspective. Every cluster carries its own
        // backdrop wall on the far side, so each shot lands against a clean, empty surface.

        // BANK KIOSK — NORTH bearing (oz - r). Quartz pad + deepslate back wall at oz-26, the Bank
        // Terminal dead centre between two quartz pillars. Slight 3/4 from the south-east.
        shots.add(shot("bank_terminal", ox + 2.5, oy + 2.6, oz - r + 4.5, ox, oy + 1.6, oz - r - 0.5));

        // PLAYER SHOP ROW — EAST bearing (ox + r). Three shops in a north-south line at z-2/z/z+2, back
        // wall at ox+26. Shot down the line from the north-west so the primed near shop reads large.
        shots.add(shot("player_shop", ox + r - 3, oy + 2.4, oz - 5.5, ox + r, oy + 1.6, oz + 0.5));

        // TRADING TERMINAL DAIS — SOUTH bearing (oz + r). Trading Terminal + Bank Terminal paired on a
        // one-block deepslate dais (so they sit at oy+2), back wall at oz+26. Elevated 3/4 from the north-west.
        shots.add(shot("trading_terminal", ox - 2.5, oy + 3.8, oz + r - 4.5, ox, oy + 2.6, oz + r + 0.5));

        // MARKET BOARD — WEST bearing (ox - r). Two boards at z-1/z+1 against a wall at ox-26, seeded
        // with live listings + standing buy offers. Near 3/4 from the south-east.
        shots.add(shot("market_board", ox - r + 4.5, oy + 2.6, oz + 3, ox - r - 0.5, oy + 1.7, oz - 0.5));

        return shots;
    }

    // ------------------------------------------------------------------------------------------------
    // Small shot builders.
    // ------------------------------------------------------------------------------------------------

    /** A camera-only gallery shot (no setup/build/cleanup; the rebuild rides on shot 0). */
    private static Shot shot(String name, double cx, double cy, double cz, double tx, double ty, double tz) {
        return new Shot(name, List.of(), List.of(), List.of(), 0, new Vec3(cx, cy, cz), new Vec3(tx, ty, tz));
    }

    /** Sweep dropped items + XP-orb litter off the ground (run after a {@link #CLEANUP_TICKS} pause). */
    private static List<String> groundClear() {
        return List.of("kill @e[type=item]", "kill @e[type=experience_orb]");
    }
}
