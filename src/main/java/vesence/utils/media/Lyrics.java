package vesence.utils.media;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import vesence.Vesence;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Тексты песен для Dynamic Island.
 *
 * <p>Формат — LRC: {@code [mm:ss.xx]строка}. Поддерживается и «усиленный» LRC
 * с таймкодами отдельных слов ({@code <mm:ss.xx>слово}). Если файл без
 * таймкодов (обычный .lrc/.txt), слова равномерно распределяются по длительности
 * трека — караоке остаётся плавным.
 *
 * <p>Источники по порядку:
 * <ol>
 *   <li>{@code <gameDir>/vesence/lyrics/<artist - title>.lrc} (или .txt) — можно
 *       положить свой текст;</li>
 *   <li>ресурс мода {@code assets/vesence/lyrics/<slug>.lrc};</li>
 *   <li>LRCLIB ({@code lrclib.net}, только название и исполнитель) — результат
 *       кэшируется на диск, дальше работает офлайн.</li>
 * </ol>
 *
 * <p>Всё грузится в отдельном демон-потоке: {@link #get} никогда не блокирует
 * рендер и возвращает {@code null}, пока текст ещё не готов.
 */
@Environment(EnvType.CLIENT)
public final class Lyrics {

    /** Слово с интервалом звучания. */
    public record Word(String text, long startMs, long endMs) {
    }

    /** Разобранный текст: плоский список слов (строки склеены). */
    public record Doc(List<Word> words, boolean synced, boolean wordSynced) {
        public Doc(List<Word> words, boolean synced) {
            this(words, synced, false);
        }
    }

    private static final Pattern LINE_TS = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern WORD_TS = Pattern.compile("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>");
    private static final int MAX_WORDS = 4000;
    private static final long MISS_RETRY_MS = 15_000L;

    private static final Map<String, Doc> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> MISS = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> ONLINE_REFRESHED = new ConcurrentHashMap<>();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vesence-lyrics");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean online = true;
    private static volatile HttpClient http;

    private Lyrics() {
    }

    /** Включает/выключает запросы к LRCLIB. */
    public static void setOnline(boolean value) {
        online = value;
    }

    /**
     * Готовый текст или {@code null}, если его нет/он ещё грузится.
     * При первом обращении запускает фоновую загрузку.
     */
    public static Doc get(String title, String artist, long durationMs) {
        if (title == null || title.isBlank()) return null;
        String key = key(title, artist, durationMs);
        Doc cached = CACHE.get(key);
        if (cached != null) {
            refreshWordSync(key, title, artist, durationMs, cached);
            return cached;
        }

        Long missedAt = MISS.get(key);
        if (missedAt != null && System.currentTimeMillis() - missedAt < MISS_RETRY_MS) return null;

        if (LOADING.add(key)) {
            EXEC.execute(() -> {
                try {
                    Doc doc = load(title, artist, durationMs);
                    if (doc != null) {
                        CACHE.put(key, doc);
                        MISS.remove(key);
                    } else {
                        MISS.put(key, System.currentTimeMillis());
                    }
                } catch (Throwable ignored) {
                    MISS.put(key, System.currentTimeMillis());
                } finally {
                    LOADING.remove(key);
                }
            });
        }
        return null;
    }

    private static void refreshWordSync(String key, String title, String artist, long durationMs, Doc current) {
        if (!online || current.wordSynced()) return;
        long now = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicBoolean shouldRefresh = new java.util.concurrent.atomic.AtomicBoolean();
        ONLINE_REFRESHED.compute(key, (ignored, lastAttempt) -> {
            if (lastAttempt == null || now - lastAttempt >= MISS_RETRY_MS) {
                shouldRefresh.set(true);
                return now;
            }
            return lastAttempt;
        });
        if (!shouldRefresh.get()) return;
        EXEC.execute(() -> {
            try {
                Doc better = fetchLrclib(title, artist == null ? "" : artist.trim(), durationMs);
                if (better != null && better.wordSynced()) {
                    CACHE.put(key, better);
                    System.out.println("[NowPlaying] lyrics: word-synced LRCLIB lyrics for " + title);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    // ── загрузка ─────────────────────────────────────────────────────────────

    private static Doc load(String title, String artist, long durationMs) {
        String a = artist == null ? "" : artist.trim();
        String t = title.trim();

        for (File file : localCandidates(a, t)) {
            if (file.isFile()) {
                Doc doc = parse(readFile(file), durationMs);
                if (doc != null) {
                    System.out.println("[NowPlaying] lyrics: local file " + file.getName());
                    return doc;
                }
            }
        }

        Doc bundled = parse(readResource(slug(t)), durationMs);
        if (bundled == null && !a.isEmpty()) bundled = parse(readResource(slug(a + " - " + t)), durationMs);
        if (bundled != null) {
            System.out.println("[NowPlaying] lyrics: bundled resource for " + t);
            return bundled;
        }

        if (online) return fetchOnline(t, a, durationMs);
        return null;
    }

    private static List<File> localCandidates(String artist, String title) {
        File dir = lyricsDir();
        List<String> names = new ArrayList<>();
        if (!artist.isEmpty()) names.add(slug(artist + " - " + title));
        names.add(slug(title));
        if (!artist.isEmpty()) {
            names.add(slug(title + " - " + artist));
            names.add(slug(artist + " " + title));
        }
        List<File> files = new ArrayList<>();
        for (String name : names) {
            files.add(new File(dir, name + ".lrc"));
            files.add(new File(dir, name + ".txt"));
        }
        return files;
    }

    private static File lyricsDir() {
        File root;
        try {
            root = Vesence.get != null ? Vesence.get.root
                    : new File(System.getProperty("user.home", "."), ".vesence");
        } catch (Throwable t) {
            root = new File(System.getProperty("user.home", "."), ".vesence");
        }
        File dir = new File(root, "lyrics");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    private static String readFile(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String readResource(String name) {
        String path = "/assets/vesence/lyrics/" + name;
        for (String ext : new String[]{".lrc", ".txt"}) {
            try (InputStream in = Lyrics.class.getResourceAsStream(path + ext)) {
                if (in == null) continue;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                    return sb.toString();
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return null;
    }

    // ── LRCLIB ───────────────────────────────────────────────────────────────

    private static Doc fetchOnline(String title, String artist, long durationMs) {
        Doc doc = fetchLrclib(title, artist, durationMs);
        if (doc != null) return doc;

        // LRCLIB недоступен (аварии бывают) — пробуем плоские источники без
        // таймкодов: слова распределяются по длительности трека, караоке остаётся.
        String plain = fetchPlainOvh(title, artist);
        if (plain == null) plain = fetchPlainChartLyrics(title, artist);
        if (plain != null) {
            Doc distributed = parse(plain, durationMs);
            if (distributed != null) {
                cacheOnlineDoc(artist, title, plain);
                return distributed;
            }
        }
        return null;
    }

    private static Doc parseLyricsfile(String source) {
        try {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(source);
            if (!(parsed instanceof Map<?, ?> root) || !(root.get("lines") instanceof List<?> lines)) return null;
            List<Word> words = new ArrayList<>();
            for (Object lineValue : lines) {
                if (!(lineValue instanceof Map<?, ?> line) || !(line.get("words") instanceof List<?> lineWords)) continue;
                List<Map<?, ?>> validWords = new ArrayList<>();
                for (Object wordValue : lineWords) if (wordValue instanceof Map<?, ?> word) validWords.add(word);
                for (int i = 0; i < validWords.size(); i++) {
                    Map<?, ?> word = validWords.get(i);
                    Object textValue = word.get("text");
                    Object startValue = word.get("start_ms");
                    if (!(textValue instanceof String text) || !(startValue instanceof Number startNumber)) continue;
                    long start = Math.max(0L, startNumber.longValue());
                    Object endValue = word.get("end_ms");
                    long end;
                    if (endValue instanceof Number endNumber) {
                        end = endNumber.longValue();
                    } else if (i + 1 < validWords.size() && validWords.get(i + 1).get("start_ms") instanceof Number next) {
                        end = next.longValue();
                    } else if (line.get("end_ms") instanceof Number lineEnd) {
                        end = lineEnd.longValue();
                    } else {
                        end = start + 250L;
                    }
                    words.add(new Word(text, start, Math.max(start + 1L, end)));
                }
            }
            if (words.isEmpty()) return null;
            words.sort(Comparator.comparingLong(Word::startMs));
            return new Doc(List.copyOf(words), true, true);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** LRCLIB: точный запрос по названию/исполнителю, затем поиск. */
    private static Doc fetchLrclib(String title, String artist, long durationMs) {
        Doc exactDoc = null;
        try {
            String exactUrl = "https://lrclib.net/api/get?track_name="
                    + URLEncoder.encode(title, StandardCharsets.UTF_8)
                    + "&artist_name=" + URLEncoder.encode(artist, StandardCharsets.UTF_8);
            if (durationMs > 0L) exactUrl += "&duration=" + (durationMs / 1000L);
            HttpResponse<String> exact = httpClient().send(
                    HttpRequest.newBuilder(URI.create(exactUrl))
                            .timeout(Duration.ofSeconds(7))
                            .header("Accept", "application/json")
                            .header("User-Agent", "weppos/1.0 (Minecraft mod)")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (exact.statusCode() == 200) {
                JsonElement result = JsonParser.parseString(exact.body());
                if (result.isJsonObject()) {
                    JsonObject obj = result.getAsJsonObject();
                    if (obj.has("hasWordSync") && obj.get("hasWordSync").getAsBoolean()
                            && hasText(obj, "lyricsfile")) {
                        Doc wordDoc = parseLyricsfile(obj.get("lyricsfile").getAsString());
                        if (wordDoc != null) return wordDoc;
                    }
                    String text = hasText(obj, "syncedLyrics")
                            ? obj.get("syncedLyrics").getAsString()
                            : hasText(obj, "plainLyrics") ? obj.get("plainLyrics").getAsString() : null;
                    Doc doc = parse(text, durationMs);
                    if (doc != null) {
                        if (doc.wordSynced()) return doc;
                        exactDoc = doc;
                    }
                }
            }

            String query = artist.isEmpty() ? title : artist + " " + title;
            String url = "https://lrclib.net/api/search?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpResponse<String> resp = httpClient().send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(7))
                            .header("Accept", "application/json")
                            .header("User-Agent", "weppos/1.0 (Minecraft mod)")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return exactDoc;

            JsonElement root = JsonParser.parseString(resp.body());
            if (!root.isJsonArray()) return exactDoc;
            JsonArray arr = root.getAsJsonArray();

            JsonObject best = null;
            JsonObject bestPlain = null;
            JsonObject bestWordSynced = null;
            for (JsonElement el : arr) {
                if (el == null || !el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("hasWordSync") && obj.get("hasWordSync").getAsBoolean()
                        && hasText(obj, "lyricsfile")) {
                    if (bestWordSynced == null || closerDuration(obj, bestWordSynced, durationMs)) {
                        bestWordSynced = obj;
                    }
                } else if (hasText(obj, "syncedLyrics")) {
                    if (best == null || closerDuration(obj, best, durationMs)) best = obj;
                } else if (hasText(obj, "plainLyrics") && bestPlain == null) {
                    bestPlain = obj;
                }
            }
            if (bestWordSynced != null) {
                Doc wordDoc = parseLyricsfile(bestWordSynced.get("lyricsfile").getAsString());
                if (wordDoc != null) {
                    if (hasText(bestWordSynced, "syncedLyrics")) {
                        cacheOnlineDoc(artist, title, bestWordSynced.get("syncedLyrics").getAsString());
                    }
                    return wordDoc;
                }
            }
            if (best == null) best = bestPlain;
            if (best == null) return exactDoc;

            String text = hasText(best, "syncedLyrics")
                    ? best.get("syncedLyrics").getAsString()
                    : best.get("plainLyrics").getAsString();
            Doc doc = parse(text, durationMs);
            if (doc != null) {
                cacheOnlineDoc(artist, title, text);
                return doc;
            }
            return exactDoc;
        } catch (Exception e) {
            return exactDoc;
        }
    }

    /** lyrics.ovh: JSON с полем lyrics, без таймкодов. Короткий таймаут — источник бывает недоступен. */
    private static String fetchPlainOvh(String title, String artist) {
        if (artist.isBlank()) return null;
        try {
            String url = "https://api.lyrics.ovh/v1/"
                    + URLEncoder.encode(artist, StandardCharsets.UTF_8) + "/"
                    + URLEncoder.encode(title, StandardCharsets.UTF_8);
            HttpResponse<String> resp = httpClient().send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(8))
                            .header("User-Agent", "weppos/1.0 (Minecraft mod)")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return null;
            JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (obj.has("lyrics") && !obj.get("lyrics").isJsonNull()) {
                String text = obj.get("lyrics").getAsString();
                return text == null || text.isBlank() ? null : text.trim();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** ChartLyrics: XML без таймкодов, последний шанс. */
    private static String fetchPlainChartLyrics(String title, String artist) {
        if (artist.isBlank()) return null;
        try {
            String url = "http://api.chartlyrics.com/apiv1.asmx/SearchLyricDirect?artist="
                    + URLEncoder.encode(artist, StandardCharsets.UTF_8) + "&song="
                    + URLEncoder.encode(title, StandardCharsets.UTF_8);
            HttpResponse<String> resp = httpClient().send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(8))
                            .header("User-Agent", "weppos/1.0 (Minecraft mod)")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return null;
            String body = resp.body();
            int from = body.indexOf("<Lyric>");
            int to = body.indexOf("</Lyric>");
            if (from < 0 || to <= from) return null;
            String text = body.substring(from + "<Lyric>".length(), to)
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                    .replace("&#39;", "'").replace("&quot;", "\"")
                    .replaceAll("(?i)<br\\s*/?>", "\n").trim();
            return text.length() < 40 ? null : text;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasText(JsonObject obj, String field) {
        return obj.has(field) && !obj.get(field).isJsonNull()
                && !obj.get(field).getAsString().isBlank();
    }

    private static boolean closerDuration(JsonObject candidate, JsonObject current, long durationMs) {
        if (durationMs <= 0) return false;
        double cd = optDouble(candidate, "duration");
        double pd = optDouble(current, "duration");
        if (cd <= 0) return false;
        if (pd <= 0) return true;
        return Math.abs(cd * 1000 - durationMs) < Math.abs(pd * 1000 - durationMs);
    }

    private static double optDouble(JsonObject obj, String field) {
        try {
            return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsDouble() : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static void cacheOnlineDoc(String artist, String title, String text) {
        try {
            String name = slug((artist == null || artist.isBlank() ? "" : artist + " - ") + title);
            File file = new File(lyricsDir(), name + ".lrc");
            Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static HttpClient httpClient() {
        HttpClient c = http;
        if (c == null) {
            synchronized (Lyrics.class) {
                c = http;
                if (c == null) {
                    c = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    http = c;
                }
            }
        }
        return c;
    }

    // ── парсинг ──────────────────────────────────────────────────────────────

    private static final class Entry {
        final long startMs;
        String text;

        Entry(long startMs, String text) {
            this.startMs = startMs;
            this.text = text;
        }
    }

    /** Restore repeated chorus lines abbreviated to an earlier phrase's suffix. */
    private static void restoreRepeatedSuffixLines(List<Entry> entries) {
        for (int first = 0; first < entries.size();) {
            String repeated = normalizedWords(entries.get(first).text);
            if (repeated.isEmpty()) {
                first++;
                continue;
            }

            int end = first + 1;
            while (end < entries.size()
                    && repeated.equals(normalizedWords(entries.get(end).text))) {
                long gap = entries.get(end).startMs - entries.get(end - 1).startMs;
                if (gap <= 0L || gap > 5000L) break;
                end++;
            }

            if (end - first >= 3) {
                String[] suffix = repeated.split(" ");
                Entry source = null;
                for (int i = first - 1; i >= 0; i--) {
                    String[] candidate = normalizedWords(entries.get(i).text).split(" ");
                    if (candidate.length > suffix.length && endsWith(candidate, suffix)) {
                        source = entries.get(i);
                        break;
                    }
                }
                if (source != null) {
                    for (int i = first; i < end; i++) entries.get(i).text = source.text;
                }
            }
            first = end;
        }
    }

    private static boolean endsWith(String[] words, String[] suffix) {
        int offset = words.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (!words[offset + i].equals(suffix[i])) return false;
        }
        return true;
    }

    private static String normalizedWords(String text) {
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private static final class Tag {
        final int start;
        final int end;
        final long timeMs;

        Tag(int start, int end, long timeMs) {
            this.start = start;
            this.end = end;
            this.timeMs = timeMs;
        }
    }

    private static Doc parse(String raw, long durationMs) {
        if (raw == null) return null;
        String text = raw.replace("\uFEFF", "")
                .replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.isEmpty()) return null;

        if (!LINE_TS.matcher(text).find()) {
            return parsePlain(text, durationMs);
        }

        List<Entry> entries = new ArrayList<>();
        for (String line : text.split("\n")) {
            Matcher m = LINE_TS.matcher(line);
            List<Long> stamps = new ArrayList<>();
            int contentStart = line.length();
            while (m.find()) {
                stamps.add(tsToMs(m));
                contentStart = m.end();
            }
            if (stamps.isEmpty()) continue;
            String body = line.substring(Math.min(contentStart, line.length())).trim();
            for (long stamp : stamps) entries.add(new Entry(stamp, body));
        }
        if (entries.isEmpty()) return null;
        entries.sort(Comparator.comparingLong(e -> e.startMs));
        restoreRepeatedSuffixLines(entries);
        boolean hasWordSync = entries.stream().anyMatch(e -> WORD_TS.matcher(e.text).find());

        List<Word> words = new ArrayList<>();
        for (int i = 0; i < entries.size() && words.size() < MAX_WORDS; i++) {
            Entry e = entries.get(i);
            if (e.text.isEmpty()) continue;
            long nextStart = i + 1 < entries.size() ? entries.get(i + 1).startMs : -1L;
            long fallbackEnd = e.startMs + estimateLineMs(e.text);
            long lineEnd = nextStart > e.startMs ? nextStart : fallbackEnd;
            appendLine(words, e.text, e.startMs, lineEnd);
        }
        if (words.isEmpty()) return null;
        words.sort(Comparator.comparingLong(Word::startMs));
        return new Doc(List.copyOf(words), true, hasWordSync);
    }

    private static Doc parsePlain(String text, long durationMs) {
        if (durationMs <= 0) return null;
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String cleaned = line.replaceAll("\\[[^]]*]", "").trim();
            if (cleaned.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(cleaned);
        }
        if (sb.length() == 0) return null;
        List<Word> words = new ArrayList<>();
        distribute(words, sb.toString(), 0L, durationMs);
        if (words.isEmpty()) return null;
        return new Doc(List.copyOf(words), true);
    }

    private static void appendLine(List<Word> out, String body, long start, long end) {
        List<Tag> tags = new ArrayList<>();
        Matcher wm = WORD_TS.matcher(body);
        while (wm.find()) tags.add(new Tag(wm.start(), wm.end(), tsToMs(wm)));

        if (tags.isEmpty()) {
            // Между LRC-строками часто есть вокальная пауза. Оставляем часть
            // интервала неподсвеченной вместо того, чтобы тянуть слова до следующего таймкода.
            long lineSpan = Math.max(0L, end - start);
            long restMs = lineSpan >= 1000L ? Math.min(500L, lineSpan * 18L / 100L) : 0L;
            long singEnd = Math.min(end - restMs, start + estimateLineMs(body));
            distribute(out, body, start, Math.max(start + 1L, singEnd));
            return;
        }

        String lead = body.substring(0, tags.get(0).start).trim();
        if (!lead.isEmpty()) {
            distribute(out, lead, start, Math.max(start + 200, tags.get(0).timeMs));
        }
        for (int i = 0; i < tags.size(); i++) {
            Tag tag = tags.get(i);
            int from = tag.end;
            int to = i + 1 < tags.size() ? tags.get(i + 1).start : body.length();
            if (to < from) continue;
            String segment = body.substring(from, to).trim();
            if (segment.isEmpty()) continue;
            long segEnd = i + 1 < tags.size()
                    ? tags.get(i + 1).timeMs
                    : Math.max(tag.timeMs + 400, end);
            distribute(out, segment, tag.timeMs, Math.max(tag.timeMs + 100, segEnd));
        }
    }

    /** Раскладывает фразу на слова пропорционально их длине между start и end. */
    private static void distribute(List<Word> out, String phrase, long start, long end) {
        String[] parts = phrase.trim().split("\\s+");
        List<String> words = new ArrayList<>(parts.length);
        int totalChars = 0;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            words.add(p);
            totalChars += p.length();
        }
        if (words.isEmpty() || totalChars <= 0) return;

        long span = Math.max(1L, end - start);
        long acc = 0L;
        for (String word : words) {
            long wStart = start + span * acc / totalChars;
            acc += word.length();
            long wEnd = start + span * acc / totalChars;
            out.add(new Word(word, wStart, Math.max(wStart + 1L, wEnd)));
        }
    }

    private static long estimateLineMs(String text) {
        int words = text.trim().split("\\s+").length;
        return Math.max(2200L, words * 600L);
    }

    private static long tsToMs(Matcher m) {
        long minutes = Long.parseLong(m.group(1));
        long seconds = Long.parseLong(m.group(2));
        String frac = m.group(3);
        long ms = 0L;
        if (frac != null && !frac.isEmpty()) {
            ms = Long.parseLong(frac);
            if (frac.length() == 1) ms *= 100L;
            else if (frac.length() == 2) ms *= 10L;
            else if (frac.length() > 3) ms /= 10L;
        }
        return (minutes * 60L + seconds) * 1000L + ms;
    }

    // ── утилиты ──────────────────────────────────────────────────────────────

    private static String key(String title, String artist, long durationMs) {
        return slug(title) + "|" + slug(artist == null ? "" : artist) + "|" + (durationMs / 1000L);
    }

    /** Имя файла: буквы (включая кириллицу) и цифры, остальное — «_». */
    private static String slug(String value) {
        if (value == null) return "";
        String lower = value.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean prevUnderscore = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean keep = Character.isLetterOrDigit(c);
            if (keep) {
                sb.append(c);
                prevUnderscore = false;
            } else if (!prevUnderscore) {
                sb.append('_');
                prevUnderscore = true;
            }
        }
        String s = sb.toString();
        while (s.startsWith("_")) s = s.substring(1);
        while (s.endsWith("_")) s = s.substring(0, s.length() - 1);
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
