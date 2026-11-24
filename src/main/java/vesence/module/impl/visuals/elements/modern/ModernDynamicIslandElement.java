package vesence.module.impl.visuals.elements.modern;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.joml.Matrix3x2fStack;
import vesence.module.api.Module;
import vesence.module.api.setting.impl.BooleanSetting;
import vesence.module.api.setting.impl.ModeSetting;
import vesence.module.impl.visuals.HudElement;
import vesence.renderengine.render.Renderer2D;
import vesence.utils.media.Lyrics;
import vesence.utils.media.NowPlaying;
import vesence.utils.other.StopWatch;
import vesence.utils.render.ColorUtil;
import vesence.utils.render.math.animation.anim.util.Animation2;
import vesence.utils.render.math.animation.anim.util.Easings;
import vesence.utils.render.text.FontObject;
import vesence.utils.render.text.FontRegistry;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamic Island — «островок» в стиле iOS на HUD.
 *
 * <p>Что показывает (порядок задаётся настройками «Приоритет 1..4»):
 * <ul>
 *   <li><b>Музыка</b> — системный плеер Windows (SMTC: Spotify, Яндекс.Музыка,
 *       браузер…) или музыкальный диск в руке. Слева эквалайзер, справа текст
 *       песни: уже пропетые слова белые, ещё не пропетые — серые. Показывается
 *       окно минимум из {@link #MIN_VISIBLE_WORDS} слов вокруг текущего.</li>
 *   <li><b>ПВП</b> — если в сайдбар-скорборде есть строка «Вы в режиме пвп 29»,
 *       островок показывает режим и обратный отсчёт.</li>
 *   <li><b>Модуль</b> — 3 секунды после включения/выключения модуля.</li>
 *   <li><b>Алерт</b> — текущая цель AttackAura.</li>
 *   <li><b>Время</b> — часы HH:mm (фолбэк).</li>
 * </ul>
 *
 * <p>Островок остаётся компактным и не меняется при наведении. Полоски эквалайзера
 * анимированы непрерывно; ширина таблетки подстраивается под текущее содержимое.
 */
@Environment(EnvType.CLIENT)
public class ModernDynamicIslandElement extends HudElement {

    // ── геометрия таблетки ───────────────────────────────────────────────────
    // Размеры — в «дизайн-пикселях» холста 1920×1080 (позиции элементов хранятся
    // так же); при рендере умножаются на ui() — масштаб текущего вьюпорта.
    private static final float PILL_H         = 36f;
    private static final float MIN_W          = 96f;
    private static final float CONTENT_MAX_W  = 300f;
    private static final float MUSIC_MAX_W    = 380f;
    private static final float PILL_LENGTH_FACTOR = 1.15f;
    private static final float PILL_PAD       = 20f;
    private static final float TEXT_SIZE      = 22f;
    private static final float SUB_TEXT_SIZE  = 16f;
    private static final float ICON_SIZE      = 8f;
    private static final float ICON_GAP       = 8f;
    private static final float DISC_SIZE      = 22f;
    private static final float DISC_GAP       = 7f;
    private static final float RADIUS_BASE    = 14f;
    private static final float BLUR_STRENGTH  = 45f;
    /** Островок всегда по центру; сверху — не впритык к краю. */
    private static final float MIN_TOP        = 24f;
    private static final float SIDE_MARGIN    = 8f;
    private static final long  MODULE_SHOW_MS = 3000L;
    private static final int[] PING_THRESHOLDS = {450, 300, 150, 75};

    // ── эквалайзер («левитирующие полоски») ──────────────────────────────────
    private static final int   EQ_COUNT    = 4;
    private static final float EQ_BAR_W    = 4f;
    private static final float EQ_GAP      = 4f;
    private static final float EQ_MIN_H    = 5f;
    private static final float EQ_MAX_H    = 18f;
    private static final float EQ_TOTAL_W  = EQ_COUNT * EQ_BAR_W + (EQ_COUNT - 1) * EQ_GAP;
    private static final float EQ_TEXT_GAP = 10f;
    private static final float[] EQ_PHASES = {0.0f, 1.9f, 4.2f, 2.7f};
    private static final float[] EQ_SPEEDS = {1.0f, 1.5f, 0.5f, 2.0f};

    // ── караоке ──────────────────────────────────────────────────────────────
    private static final int MIN_VISIBLE_WORDS = 4;
    private static final int MAX_VISIBLE_WORDS = 7;
    private static final int LYRIC_WINDOW_SHIFT = 4;
    private static final long LYRIC_PREROLL_MS = 1200L;
    /** Разрыв между словами, считающийся паузой в песне. */
    private static final long LYRIC_PAUSE_MS = 4000L;
    private static final int SUNG_ALPHA        = 250;
    private static final int UNSUNG_ALPHA      = 108;

    // ── приоритеты (настройки в ПКМ-меню модуля) ─────────────────────────────
    private static final String CHOICE_MUSIC  = "Музыка";
    private static final String CHOICE_PVP    = "ПВП";
    private static final String CHOICE_MODULE = "Модуль";
    private static final String CHOICE_ALERT  = "Алерт";
    private static final String CHOICE_TIME   = "Время";
    private static final String CHOICE_NONE   = "Нет";
    private static final String[] CHOICES = {
            CHOICE_MUSIC, CHOICE_PVP, CHOICE_MODULE, CHOICE_ALERT, CHOICE_TIME, CHOICE_NONE
    };

    private final ModeSetting priority1 = new ModeSetting("Приоритет 1", CHOICE_MUSIC, CHOICES);
    private final ModeSetting priority2 = new ModeSetting("Приоритет 2", CHOICE_PVP, CHOICES);
    private final ModeSetting priority3 = new ModeSetting("Приоритет 3", CHOICE_MODULE, CHOICES);
    private final ModeSetting priority4 = new ModeSetting("Приоритет 4", CHOICE_ALERT, CHOICES);
    private final ModeSetting[] priorities = {priority1, priority2, priority3, priority4};

    public final BooleanSetting systemMedia = new BooleanSetting("Системная музыка", true);
    public final BooleanSetting onlineLyrics = new BooleanSetting("Онлайн-тексты", true);

    // ── анимации ─────────────────────────────────────────────────────────────
    private final Animation2 widthAnim      = new Animation2();
    private final Animation2 visibilityAnim = new Animation2();
    private final Animation2 lyricSlideAnim = new Animation2();

    /** Хук переключения модуля: имя + включён/выключен (порт Module.setEnabled). */
    private static volatile String lastToggledModule;
    private static volatile boolean lastToggledState;
    private static final StopWatch moduleWatch = new StopWatch();

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final double BPS_WINDOW_SECONDS = 0.25;

    private static final Pattern SIDEBAR_CLOCK_ANY =
            Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Pattern SIDEBAR_CLOCK_END =
            Pattern.compile("(\\d{1,2})\\s*:\\s*(\\d{2})\\s*(?:с|сек|s|sec)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIDEBAR_NUMBER_END =
            Pattern.compile("(\\d{1,5})\\s*(?:с|сек|s|sec)?\\s*$", Pattern.CASE_INSENSITIVE);

    private float boundsW = MIN_W;
    private float boundsH = PILL_H;

    /**
     * Масштаб вьюпорта относительно дизайн-холста 1920×1080 (позиции элементов
     * нормируются к тому же холсту). На 1920×1080 равен 1 — размеры выше
     * соответствуют экрану пользователя один в один.
     */
    private float ui = 1f;

    private long musicStartMs = 0L;
    private String musicDiscName = null;

    // Анти-дребезг караоке: сглаженная позиция и удержание окна слов.
    private long smoothPositionMs = -1L;
    private long smoothFrameNanos = 0L;
    private String karaokeTrackKey = null;
    /** Фактические координаты отрисовки: островок центрируется по экрану. */
    private float drawX = 0f;
    private float drawY = 0f;
    /** Плавный сдвиг строки при переключении окна слов. */
    private float lyricSlidePx = 0f;
    private int prevLyricStart = -1;

    // Скользящее окно BPS (как в watermark).
    private double bpsStartX = Double.NaN;
    private double bpsStartZ = Double.NaN;
    private long bpsStartNanos = 0L;
    private double displayedBps = 0.0;

    public ModernDynamicIslandElement() {
        super("Dynamic Island", 320f, 7f);
        widthAnim.set(MIN_W);
        visibilityAnim.set(0.0);

        addSetting(systemMedia);
        addSetting(onlineLyrics);
        for (ModeSetting mode : priorities) addModeSetting(mode);
    }

    /** Вызывается из {@link Module#toggle()}. */
    public static void onModuleToggle(String name, boolean enabled) {
        lastToggledModule = name;
        lastToggledState = enabled;
        moduleWatch.reset();
    }

    private enum Mode { MUSIC, PVP, MODULE, ALERT, DEFAULT }

    private static final class IslandState {
        Mode mode = Mode.DEFAULT;
        String title = "";
        String subtitle;
        String timer;
        float progress = -1f;
        boolean playing = true;
        ItemStack disc;
        Lyrics.Doc lyrics;
        long positionMs;
        long durationMs;
        boolean positive;
    }

    private record Token(String text, long startMs, long endMs) {
    }

    // ── выбор состояния по приоритетам ───────────────────────────────────────

    private IslandState computeState(Minecraft mc) {
        for (ModeSetting priority : priorities) {
            String choice = priority.get();
            if (choice == null || CHOICE_NONE.equals(choice)) continue;
            if (CHOICE_TIME.equals(choice)) return defaultState();

            IslandState state = switch (choice) {
                case CHOICE_MUSIC -> musicState(mc);
                case CHOICE_PVP -> pvpState(mc);
                case CHOICE_MODULE -> moduleState();
                case CHOICE_ALERT -> alertState();
                default -> null;
            };
            if (state != null) return state;
        }
        return defaultState();
    }

    private static IslandState defaultState() {
        IslandState state = new IslandState();
        state.mode = Mode.DEFAULT;
        state.title = LocalTime.now().format(TIME_FORMAT);
        return state;
    }

    /** Системный плеер Windows, иначе — музыкальный диск в руке. */
    private IslandState musicState(Minecraft mc) {
        if (systemMedia.get() && NowPlaying.isSupported()) {
            NowPlaying.Track track = NowPlaying.current();
            if (track != null) {
                IslandState state = new IslandState();
                state.mode = Mode.MUSIC;
                state.title = track.title();
                state.subtitle = track.artist();
                state.positionMs = track.interpolatedPositionMs();
                state.durationMs = track.durationMs();
                state.playing = track.playing();
                if (state.durationMs > 0L) {
                    state.progress = clamp01(state.positionMs / (float) state.durationMs);
                }
                state.lyrics = Lyrics.get(track.title(), track.artist(), state.durationMs);
                return state;
            }
        }

        if (mc.player == null) return null;
        ItemStack main = mc.player.getMainHandItem();
        ItemStack hand = main != null && isMusicDisc(main) ? main : mc.player.getOffhandItem();
        if (hand == null || !isMusicDisc(hand)) {
            musicDiscName = null;
            musicStartMs = 0L;
            return null;
        }

        String name = discName(hand);
        long now = System.currentTimeMillis();
        if (!name.equals(musicDiscName)) {
            musicDiscName = name;
            musicStartMs = now;
        }
        long lengthMs = discLengthMs(hand);

        IslandState state = new IslandState();
        state.mode = Mode.MUSIC;
        state.title = name;
        state.disc = hand;
        state.durationMs = lengthMs;
        long elapsed = Math.max(0L, now - musicStartMs);
        state.positionMs = lengthMs > 0L ? elapsed % lengthMs : elapsed;
        state.progress = lengthMs > 0L ? clamp01(state.positionMs / (float) lengthMs) : -1f;
        state.lyrics = Lyrics.get(name, "", lengthMs);
        return state;
    }

    private IslandState moduleState() {
        if (lastToggledModule == null || moduleWatch.finished(MODULE_SHOW_MS)) return null;
        IslandState state = new IslandState();
        state.mode = Mode.MODULE;
        state.title = lastToggledModule;
        state.timer = lastToggledState ? "ВКЛ" : "ВЫКЛ";
        state.positive = lastToggledState;
        return state;
    }

    private IslandState alertState() {
        var aura = vesence.Vesence.get != null
                ? vesence.Vesence.get.manager.get(vesence.module.impl.combat.AttackAura.class)
                : null;
        if (aura == null || !aura.enable || vesence.module.impl.combat.AttackAura.target == null) {
            return null;
        }
        var target = vesence.module.impl.combat.AttackAura.target;
        IslandState state = new IslandState();
        state.mode = Mode.ALERT;
        state.title = target.getName().getString();
        state.timer = Math.round(target.getHealth()) + " HP";
        state.positive = true;
        return state;
    }

    // ── ПВП из сайдбар-скорборда ─────────────────────────────────────────────

    private IslandState pvpState(Minecraft mc) {
        if (mc.level == null || mc.player == null) return null;
        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective objective = resolveSidebarObjective(scoreboard, mc);
        if (objective == null) return null;

        List<PlayerScoreEntry> entries = new ArrayList<>(scoreboard.listPlayerScores(objective));
        entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());

        IslandState withoutTimer = null;
        for (PlayerScoreEntry entry : entries) {
            if (entry == null || entry.isHidden()) continue;
            String text = cleanSidebarLine(entryText(scoreboard, entry));
            if (text.isEmpty()) continue;

            String lower = text.toLowerCase(Locale.ROOT);
            if (!lower.contains("пвп") && !lower.contains("pvp")) continue;

            Long seconds = extractTimerSeconds(text);
            IslandState state = new IslandState();
            state.mode = Mode.PVP;
            if (seconds != null) {
                state.title = trimSeparators(cutTrailingTimer(text));
                if (state.title.isBlank()) state.title = "ПВП";
                state.timer = formatTimer(seconds);
                return state;
            }
            state.title = text;
            if (withoutTimer == null) withoutTimer = state;
        }
        return withoutTimer;
    }

    private static Objective resolveSidebarObjective(Scoreboard scoreboard, Minecraft mc) {
        if (scoreboard == null) return null;
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (mc.player == null) return sidebar;

        Team team = scoreboard.getPlayersTeam(mc.player.getName().getString());
        if (team != null) {
            var color = team.getColor();
            if (color.isPresent()) {
                Objective teamObjective = scoreboard.getDisplayObjective(color.get().displaySlot());
                if (teamObjective != null) return teamObjective;
            }
        }
        return sidebar;
    }

    /** Текст строки лежит в prefix/suffix команды — {@code Team.decorateName}. */
    private static String entryText(Scoreboard scoreboard, PlayerScoreEntry entry) {
        Component base = entry.ownerName();
        if (base == null) base = Component.empty();
        Team team = entry.owner() != null ? scoreboard.getPlayersTeam(entry.owner()) : null;
        return net.minecraft.world.scores.PlayerTeam.formatNameForTeam(team, base).getString();
    }

    private static String cleanSidebarLine(String raw) {
        if (raw == null) return "";
        String s = ChatFormatting.stripFormatting(raw);
        s = s.replaceAll("&#[0-9a-fA-F]{6}", "");
        s = s.replaceAll("(?i)[\u00A7&$][0-9a-fk-orx]", "");
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20) continue;
            if (c >= 0x2500 && c <= 0x27BF) continue; // псевдографика/декор сервера
            out.append(c);
        }
        return out.toString().replaceAll("\\s{2,}", " ").trim();
    }

    private static Long extractTimerSeconds(String text) {
        Matcher clock = SIDEBAR_CLOCK_ANY.matcher(text);
        Long lastClock = null;
        while (clock.find()) {
            lastClock = Long.parseLong(clock.group(1)) * 60L + Long.parseLong(clock.group(2));
        }
        if (lastClock != null) return lastClock;

        Matcher number = SIDEBAR_NUMBER_END.matcher(text);
        if (number.find()) {
            try {
                return Long.parseLong(number.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static String cutTrailingTimer(String text) {
        Matcher clock = SIDEBAR_CLOCK_END.matcher(text);
        if (clock.find()) return text.substring(0, clock.start());
        Matcher number = SIDEBAR_NUMBER_END.matcher(text);
        if (number.find()) return text.substring(0, number.start());
        return text;
    }

    private static String trimSeparators(String text) {
        String s = text == null ? "" : text.trim();
        while (!s.isEmpty()) {
            char c = s.charAt(s.length() - 1);
            if (c == ':' || c == '-' || c == '—' || c == '|' || c == '.' || c == '·' || c == ',') {
                s = s.substring(0, s.length() - 1).trim();
            } else {
                break;
            }
        }
        return s;
    }

    private static String formatTimer(long seconds) {
        if (seconds <= 0L) return "0с";
        if (seconds < 60L) return seconds + "с";
        long minutes = seconds / 60L;
        long secs = seconds % 60L;
        if (minutes >= 60L) {
            return (minutes / 60L) + ":" + String.format(Locale.ROOT, "%02d", minutes % 60L)
                    + ":" + String.format(Locale.ROOT, "%02d", secs);
        }
        return minutes + ":" + String.format(Locale.ROOT, "%02d", secs);
    }

    // ── музыка ───────────────────────────────────────────────────────────────

    private static boolean isMusicDisc(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            return JukeboxSong.fromStack(stack).isPresent();
        } catch (Exception e) {
            return stack.is(Items.MUSIC_DISC_CAT) || stack.is(Items.MUSIC_DISC_PIGSTEP);
        }
    }

    private static String discName(ItemStack stack) {
        try {
            var holderOpt = JukeboxSong.fromStack(stack);
            if (holderOpt.isPresent()) {
                var desc = holderOpt.get().value().description();
                if (desc != null) {
                    String s = desc.getString();
                    if (s != null && !s.isEmpty()) return s;
                }
            }
        } catch (Exception ignored) {
        }
        return "Music";
    }

    private static long discLengthMs(ItemStack stack) {
        try {
            var holderOpt = JukeboxSong.fromStack(stack);
            if (holderOpt.isPresent()) {
                float seconds = holderOpt.get().value().lengthInSeconds();
                if (seconds > 0f) return (long) (seconds * 1000f);
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static int ping(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) return -1;
        PlayerInfo entry = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return entry == null ? -1 : entry.getLatency();
    }

    private double updateAndGetBps(Minecraft mc) {
        if (mc.player == null) {
            bpsStartX = Double.NaN;
            bpsStartZ = Double.NaN;
            displayedBps = 0.0;
            return 0.0;
        }
        double curX = mc.player.getX();
        double curZ = mc.player.getZ();
        long now = System.nanoTime();
        if (Double.isNaN(bpsStartX)) {
            bpsStartX = curX;
            bpsStartZ = curZ;
            bpsStartNanos = now;
            return displayedBps;
        }
        double elapsed = (now - bpsStartNanos) / 1_000_000_000.0;
        if (elapsed >= BPS_WINDOW_SECONDS) {
            double dx = curX - bpsStartX;
            double dz = curZ - bpsStartZ;
            displayedBps = Math.sqrt(dx * dx + dz * dz) / elapsed;
            bpsStartX = curX;
            bpsStartZ = curZ;
            bpsStartNanos = now;
        }
        return displayedBps;
    }

    // ── рендер ───────────────────────────────────────────────────────────────

    @Override
    public void render(Renderer2D renderer, FontObject font, int screenWidth, int screenHeight, GuiGraphicsExtractor ctx) {
        if (FontRegistry.SF_SEMI == null) return;
        FontObject f = FontRegistry.SF_SEMI;

        Minecraft mc = Minecraft.getInstance();
        Lyrics.setOnline(onlineLyrics.get());
        IslandState state = computeState(mc);
        ui = 1f;

        float elemScale = getScale();

        int ping = ping(mc);
        double bps = updateAndGetBps(mc);

        String pingValue = ping >= 0 ? ping + " ms" : "n/a";
        String fpsValue  = mc.getFps() + " FPS";
        String bpsValue  = String.format(Locale.ROOT, "%.1f BPS", bps);
        String posValue  = playerPosition(mc);

        // ---- Содержимое таблетки ----
        boolean music = state.mode == Mode.MUSIC;
        boolean hasDisc = state.disc != null;

        float leadW;
        if (music) {
            leadW = EQ_TOTAL_W * ui + EQ_TEXT_GAP * ui + (hasDisc ? DISC_SIZE * ui + DISC_GAP * ui : 0f);
        } else {
            leadW = ICON_SIZE * ui + ICON_GAP * ui;
        }

        // Окно караоке: ширина стабильная, чтобы островок не «дышал» каждым словом.
        List<Token> lyricTokens = null;
        float lyricsWidth = 0f;
        long karaokePos = state.positionMs;
        if (music && state.lyrics != null) {
            float sSafe = Math.max(0.01f, elemScale);
            float availableW = screenWidth - SIDE_MARGIN * 2f;
            float maxMusicW = Math.min(MUSIC_MAX_W * ui,
                    Math.max(MIN_W * ui, availableW / sSafe));
            float lyricTextW = Math.max(60f * ui, maxMusicW - PILL_PAD * 2f * ui - leadW);

            // Позиция для караоке: SMTC между опросами может отдавать время с
            // откатом назад — из-за этого текущее слово мигает. Двигаем позицию
            // только вперёд; назад — лишь при явной перемотке (>2.5 с).
            karaokePos = state.positionMs;
            String trackKey = state.title + "|" + state.durationMs;
            if (!trackKey.equals(karaokeTrackKey)) {
                karaokeTrackKey = trackKey;
                smoothPositionMs = karaokePos;
                prevLyricStart = -1;
            } else if (!state.playing) {
                smoothPositionMs = karaokePos;
            } else {
                // Позиция идёт непрерывно со скоростью времени, а ошибку относительно
                // плеера «подтягиваем» плавно — без рывков вперёд/назад. Скачок
                // больше 2.5 с (перемотка) принимаем сразу.
                long err = karaokePos - smoothPositionMs;
                if (Math.abs(err) > 2500L) {
                    smoothPositionMs = karaokePos;
                } else {
                    long nowN = System.nanoTime();
                    float dt = smoothFrameNanos == 0L ? 0f : Math.min(0.1f, (nowN - smoothFrameNanos) / 1e9f);
                    smoothPositionMs += Math.round(dt * 1000f + err * Math.min(1f, dt * 3f));
                }
            }
            smoothFrameNanos = System.nanoTime();
            if (smoothPositionMs >= 0L) karaokePos = smoothPositionMs;

            lyricTokens = buildLyricTokens(renderer, f, state.lyrics, karaokePos, lyricTextW);
            if (lyricTokens != null) {
                float wordsW = 0f;
                for (Token token : lyricTokens) {
                    wordsW += measure(renderer, f, token.text(), TEXT_SIZE * ui);
                }
                wordsW += Math.max(0, lyricTokens.size() - 1) * spaceWidth(renderer, f);
                lyricsWidth = Math.min(maxMusicW, PILL_PAD * 2f * ui + leadW + wordsW);
            }
        }

        float maxPillW = music ? MUSIC_MAX_W * ui : CONTENT_MAX_W * ui;
        maxPillW = Math.min(maxPillW,
                Math.max(MIN_W * ui, (screenWidth - SIDE_MARGIN * 2f) / Math.max(0.01f, elemScale)));

        float subW = 0f;
        String subText = null;
        if (state.subtitle != null && !state.subtitle.isBlank()) {
            subText = " · " + state.subtitle.trim();
            subW = measure(renderer, f, subText, SUB_TEXT_SIZE * ui);
        }

        float timerW = 0f;
        if (state.timer != null) {
            timerW = 10f * ui + measure(renderer, f, state.timer, TEXT_SIZE * ui);
        }

        String displayText = state.title == null ? "" : state.title;
        float allowedTitleW = Math.max(30f * ui, maxPillW - PILL_PAD * 2f * ui - leadW - subW - timerW);
        displayText = ellipsize(renderer, f, displayText, allowedTitleW, TEXT_SIZE * ui);
        float titleW = measure(renderer, f, displayText, TEXT_SIZE * ui);

        float pillW;
        float groupW = 0f;
        if (lyricTokens != null) {
            pillW = lyricsWidth;
        } else {
            groupW = leadW + titleW + subW + timerW;
            // Таблетка в 1.5 раза длиннее содержимого; содержимое центрируется.
            float factor = music ? 1f : PILL_LENGTH_FACTOR; // музыка и длинные названия — без растяжки
            pillW = Math.min(maxPillW, (PILL_PAD * 2f * ui + groupW) * factor);
        }

        float targetW = pillW;
        float targetH = PILL_H * ui;

        // Ширина анимируется коротко (0.2 с): сдвиг окна слов не «прыгает»,
        // но и заметного дыхания таблетки нет.
        widthAnim.update();
        widthAnim.run(Math.round(targetW), 0.2, Easings.CUBIC_OUT);
        visibilityAnim.update();
        visibilityAnim.run(1.0, 0.5, Easings.CUBIC_OUT);
        float rectW = Math.round((float) widthAnim.get());
        float rectH = targetH;
        float radius = RADIUS_BASE;
        float globalAlpha = (float) visibilityAnim.get();
        if (globalAlpha < 0.005f) return;

        // Первый запуск (или сброс позиции): ставим островок по центру сверху.
        // Дальше он живёт по обычным координатам HUD-элемента, чтобы его можно
        // было перетаскивать и чтобы hitbox совпадал с картинкой.
        // Зафиксирован: всегда по центру экрана по горизонтали, с отступом сверху.
        x = Math.round((screenWidth - rectW * elemScale) / 2f);
        y = MIN_TOP;
        targetX = x;
        targetY = y;
        targetInitialized = true;

        drawX = Math.round(x);
        drawY = Math.round(y);

        int themeColor = Renderer2D.ColorUtil.getClientColor();

        // ---- Фон: blur 45 + чёрный squircle 85% ----
        if (isBlurEnabled()) {
            renderer.blur(drawX, drawY, rectW, rectH, BLUR_STRENGTH, radius, globalAlpha);
        }
        renderer.shadow(drawX, drawY, rectW, rectH, radius, 8f * ui, 1f,
                ColorUtil.replAlpha(ColorUtil.BLACK, (int) (55 * globalAlpha)));
        renderer.rect(drawX, drawY, rectW, rectH, radius,
                ColorUtil.getColor(0, 0, 0, (int) (216.75f * globalAlpha)));

        // ---- Верхняя строка (таблетка) ----
        float centerY = drawY + PILL_H * ui / 2f;

        if (state.playing) {
            lyricSlideAnim.update();
        } else {
            // На паузе не продолжаем двигать окно; завершаем текущий переход.
            lyricSlideAnim.set(0.0);
        }
        float slide = lyricSlidePx * (float) lyricSlideAnim.get();

        renderer.pushClipRect(drawX, drawY, rectW, PILL_H * ui);
        try {
            float contentX = Math.round(lyricTokens != null
                    ? drawX + PILL_PAD * ui
                    : drawX + (rectW - groupW) / 2f);

            if (music) {
                if (state.playing) {
                    drawEqualizer(renderer, contentX, centerY, themeColor, globalAlpha);
                } else {
                    // Пауза: полоски замерли на минимальной высоте.
                    for (int i = 0; i < EQ_COUNT; i++) {
                        float h = EQ_MIN_H * ui;
                        float barX = contentX + i * (EQ_BAR_W + EQ_GAP) * ui;
                        renderer.rect(barX, centerY - h / 2f, EQ_BAR_W * ui, h, EQ_BAR_W * ui / 2f,
                                ColorUtil.replAlpha(themeColor, (int) (160 * globalAlpha)));
                    }
                }
                contentX += EQ_TOTAL_W * ui + EQ_TEXT_GAP * ui;
                if (hasDisc) {
                    drawItemIcon(ctx, state.disc, contentX, centerY - DISC_SIZE * ui / 2f, DISC_SIZE * ui, globalAlpha);
                    contentX += DISC_SIZE * ui + DISC_GAP * ui;
                }
            } else {
                int dotColor;
                if (state.mode == Mode.MODULE) {
                    dotColor = state.positive
                            ? ColorUtil.getColor(34, 187, 94)
                            : ColorUtil.getColor(239, 68, 68);
                } else if (state.mode == Mode.PVP) {
                    dotColor = ColorUtil.getColor(239, 68, 68);
                } else {
                    dotColor = themeColor;
                }
                renderer.rect(contentX, centerY - ICON_SIZE * ui / 2f, ICON_SIZE * ui, ICON_SIZE * ui, ICON_SIZE * ui / 2f,
                        ColorUtil.replAlpha(dotColor, (int) (220 * globalAlpha)));
                contentX += ICON_SIZE * ui + ICON_GAP * ui;
            }

            if (lyricTokens != null) {
                float baseline = baselineFor(centerY, TEXT_SIZE * ui);
                float cursor = contentX + slide;
                float spaceW = spaceWidth(renderer, f);
                for (Token token : lyricTokens) {
                    float span = Math.max(1L, token.endMs() - token.startMs());
                    float fraction = clamp01((karaokePos - token.startMs()) / span);
                    float width = measure(renderer, f, token.text(), TEXT_SIZE * ui);
                    renderer.text(f, cursor, baseline, TEXT_SIZE * ui, token.text(),
                            ColorUtil.replAlpha(-1, (int) (UNSUNG_ALPHA * globalAlpha)), -0.05f);
                    float sungWidth = width * fraction;
                    if (sungWidth > 0.01f) {
                        renderer.pushClipRect(cursor, drawY, sungWidth, PILL_H * ui);
                        try {
                            renderer.text(f, cursor, baseline, TEXT_SIZE * ui, token.text(),
                                    ColorUtil.replAlpha(-1, (int) (SUNG_ALPHA * globalAlpha)), -0.05f);
                        } finally {
                            renderer.popClipRect();
                        }
                    }
                    cursor += width + spaceW;
                }
            } else {
                renderer.text(f, contentX, baselineFor(centerY, TEXT_SIZE * ui), TEXT_SIZE * ui, displayText,
                        ColorUtil.replAlpha(-1, (int) (245 * globalAlpha)), -0.05f);
                contentX += titleW;
                if (subText != null) {
                    renderer.text(f, contentX, baselineFor(centerY, SUB_TEXT_SIZE * ui), SUB_TEXT_SIZE * ui, subText,
                            ColorUtil.replAlpha(-1, (int) (120 * globalAlpha)), -0.05f);
                    contentX += subW;
                }
                if (state.timer != null) {
                    int timerColor = state.mode == Mode.MODULE
                            ? (state.positive ? ColorUtil.getColor(34, 187, 94) : ColorUtil.getColor(239, 68, 68))
                            : (state.mode == Mode.PVP ? ColorUtil.getColor(255, 92, 92) : themeColor);
                    renderer.text(f, contentX + 10f * ui, baselineFor(centerY, TEXT_SIZE * ui), TEXT_SIZE * ui, state.timer,
                            ColorUtil.replAlpha(timerColor, (int) (235 * globalAlpha)), -0.05f);
                }
            }
        } finally {
            renderer.popClipRect();
        }

        // Hover no longer expands the island; keep its bounds limited to the pill.
        boundsW = rectW;
        boundsH = rectH;
    }

    /** Эквалайзер: 4 полоски с индивидуальными фазами (Nested1_cb9fdd80). */
    private void drawEqualizer(Renderer2D renderer, float leftX, float centerY, int color, float alpha) {
        float time = System.nanoTime() / 1_000_000_000f;
        for (int i = 0; i < EQ_COUNT; i++) {
            float h = (EQ_MIN_H + (EQ_MAX_H - EQ_MIN_H)
                    * (0.5f + 0.5f * (float) Math.sin(time * 4f * EQ_SPEEDS[i] + EQ_PHASES[i]))) * ui;
            float barX = leftX + i * (EQ_BAR_W + EQ_GAP) * ui;
            renderer.rect(barX, centerY - h / 2f, EQ_BAR_W * ui, h, EQ_BAR_W * ui / 2f,
                    ColorUtil.replAlpha(color, (int) (235 * alpha)));
        }
    }

    /**
     * Окно слов вокруг текущего: минимум {@link #MIN_VISIBLE_WORDS}, максимум
     * {@link #MAX_VISIBLE_WORDS}, в пределах доступной ширины. Слова с
     * {@code startMs <= positionMs} — уже пропетые (белые).
     */
    private List<Token> buildLyricTokens(Renderer2D renderer, FontObject f,
                                         Lyrics.Doc doc, long positionMs, float maxTextW) {
        List<Lyrics.Word> words = doc.words();
        int n = words.size();
        if (n == 0) return null;

        // Последнее слово, которое уже началось. Слова отсортированы по времени,
        // поэтому ищем бинарным поиском — одно «битое» слово не уводит окно.
        int lo = 0, hi = n - 1, current = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (words.get(mid).startMs() <= positionMs) {
                current = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }

        // Не сдвигаем окно на каждом новом слове: держим его на месте и
        // прокручиваем короткими блоками с перекрытием уже видимых слов.
        int start = current < 0 ? 0
                : Math.max(0, (Math.max(0, current - 2) / LYRIC_WINDOW_SHIFT) * LYRIC_WINDOW_SHIFT);

        // Пауза в песне: держим последние слова на экране и переключаемся на
        // следующую фразу только прямо перед её началом.
        if (current >= 0 && current + 1 < n) {
            long nextStart = words.get(current + 1).startMs();
            if (nextStart - words.get(current).startMs() > LYRIC_PAUSE_MS
                    && nextStart - positionMs <= LYRIC_PREROLL_MS) {
                start = current + 1;
            }
        }
        // Окно никогда не едет назад при обычном воспроизведении (только при перемотке).
        if (prevLyricStart >= 0 && start < prevLyricStart && prevLyricStart <= current) {
            start = prevLyricStart;
        }

        List<Token> tokens = fitWindow(renderer, f, words, start, maxTextW);
        // Текущее слово обязано быть в окне (и желательно не последним):
        // иначе при узком островке текст «застывает» на старом куске песни.
        int guard = 0;
        while (current >= 0 && start < current && guard++ < n
                && start + tokens.size() <= Math.min(current + 1, n - 1)) {
            start = Math.min(current, start + LYRIC_WINDOW_SHIFT);
            tokens = fitWindow(renderer, f, words, start, maxTextW);
        }

        if (start != prevLyricStart && prevLyricStart >= 0) {
            float shift = 0f;
            if (start > prevLyricStart && start - prevLyricStart < 30) {
                for (int i = prevLyricStart; i < start && i < n; i++) {
                    shift += measure(renderer, f, words.get(i).text(), TEXT_SIZE * ui) + spaceWidth(renderer, f);
                }
            } else if (start < prevLyricStart && prevLyricStart - start < 30) {
                for (int i = start; i < prevLyricStart && i < n; i++) {
                    shift -= measure(renderer, f, words.get(i).text(), TEXT_SIZE * ui) + spaceWidth(renderer, f);
                }
            }
            // Большой скачок (перемотка, смена фразы после паузы) — без «пролёта».
            lyricSlidePx = shift;
            lyricSlideAnim.set(shift == 0f ? 0.0 : 1.0);
            lyricSlideAnim.run(0.0, 0.28, Easings.CUBIC_OUT);
        }
        prevLyricStart = start;

        float width = 0f;
        float spaceW = spaceWidth(renderer, f);
        for (Token t : tokens) width += measure(renderer, f, t.text(), TEXT_SIZE * ui) + spaceW;
        while (tokens.size() < MIN_VISIBLE_WORDS && start > 0) {
            Lyrics.Word word = words.get(--start);
            float pieceW = measure(renderer, f, word.text(), TEXT_SIZE * ui) + spaceW;
            if (width + pieceW > maxTextW) break;
            tokens.add(0, new Token(word.text(), word.startMs(), word.endMs()));
            width += pieceW;
        }
        return tokens.isEmpty() ? null : tokens;
    }

    /** Слова с позиции {@code start}, пока влезают в {@code maxTextW} (хотя бы одно). */
    private List<Token> fitWindow(Renderer2D renderer, FontObject f, List<Lyrics.Word> words,
                                  int start, float maxTextW) {
        List<Token> tokens = new ArrayList<>();
        float width = 0f;
        for (int i = start; i < words.size(); i++) {
            Lyrics.Word word = words.get(i);
            float pieceW = measure(renderer, f, word.text(), TEXT_SIZE * ui)
                    + (tokens.isEmpty() ? 0f : spaceWidth(renderer, f));
            if (!tokens.isEmpty() && width + pieceW > maxTextW) break;
            tokens.add(new Token(word.text(), word.startMs(), word.endMs()));
            width += pieceW;
            if (tokens.size() >= MAX_VISIBLE_WORDS) break;
        }
        return tokens;
    }

    /** Ширина пробела, измеренная явно (в одиночной строке с пробелом он может «схлопываться»). */
    private float spaceWidth(Renderer2D renderer, FontObject f) {
        float size = TEXT_SIZE * ui;
        float w = measure(renderer, f, "a a", size) - measure(renderer, f, "aa", size);
        return w > 1f ? w : size * 0.28f;
    }

    private static String ellipsize(Renderer2D renderer, FontObject f, String text,
                                    float maxW, float size) {
        if (text == null) return "";
        if (maxW <= 0f) return "";
        if (measure(renderer, f, text, size) <= maxW) return text;
        String s = text;
        while (s.length() > 1) {
            s = s.substring(0, s.length() - 1);
            String candidate = s + "…";
            if (measure(renderer, f, candidate, size) <= maxW) return candidate;
        }
        return "…";
    }

    /** Смещение baseline, при котором центр тела глифов попадает в {@code centerY}. */
    private static float baselineFor(float centerY, float size) {
        return Math.round(centerY + 0.46f * size / 2f);
    }

    private static float measure(Renderer2D renderer, FontObject f, String text, float size) {
        return renderer.measureText(f, text, size, -0.05f).width;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static String playerPosition(Minecraft mc) {
        if (mc.player == null) return "0 / 0 / 0";
        return (int) Math.floor(mc.player.getX()) + " / "
                + (int) Math.floor(mc.player.getY()) + " / "
                + (int) Math.floor(mc.player.getZ());
    }

    private void drawItemIcon(GuiGraphicsExtractor ctx, ItemStack stack, float px, float py, float size, float alpha) {
        if (stack == null || stack.isEmpty() || alpha <= 0.01f) return;
        float elemScale = getScale();
        float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
        float sx = drawX + (px - drawX) * elemScale;
        float sy = drawY + (py - drawY) * elemScale;
        float drawSize = size * elemScale;

        Matrix3x2fStack m = ctx.pose();
        m.pushMatrix();
        float cx = (sx + drawSize / 2f) / guiScale;
        float cy = (sy + drawSize / 2f) / guiScale;
        m.translate(cx, cy);
        float sc = (drawSize / guiScale) / 16f * alpha;
        m.scale(sc, sc);
        m.translate(-8f, -8f);
        ctx.item(stack, 0, 0);
        m.popMatrix();
    }

    /** Островок зафиксирован: не наводится, не перетаскивается, без анимаций press/hover. */
    @Override
    public boolean isHovered(double mouseX, double mouseY, Renderer2D renderer, FontObject font) {
        return false;
    }

    @Override
    public float getEffectiveWidth(Renderer2D renderer, FontObject font) {
        return boundsW;
    }

    @Override
    public float getWidth(Renderer2D renderer, FontObject font) {
        return boundsW > 0f ? boundsW : MIN_W;
    }

    @Override
    public float getHeight(Renderer2D renderer, FontObject font) {
        return boundsH > 0f ? boundsH : PILL_H;
    }
}
