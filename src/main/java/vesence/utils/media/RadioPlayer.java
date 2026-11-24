package vesence.utils.media;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Интернет-радио: тянет MP3-поток (Icecast/Shoutcast), декодирует через JLayer и
 * проигрывает на звуковую карту ({@code javax.sound.sampled}). Независимо от
 * движка звука Minecraft — обычный системный вывод.
 *
 * <p>Один живой поток воспроизведения; смена станции/остановка безопасны за счёт
 * идентификатора сессии ({@link #session}). Декодер крутится в демон-потоке и не
 * трогает рендер; наружу отдаёт только volatile-снимки (станция, уровень сигнала).
 *
 * <p>Уровень {@link #level()} (0..1) считается из амплитуды PCM — им «дышат»
 * полоски эквалайзера на HUD.
 */
@Environment(EnvType.CLIENT)
public final class RadioPlayer {

    /** Станция: отображаемое имя + прямой URL MP3-потока. */
    public record Station(String name, String url) {}

    /**
     * Список станций (подтверждённые MP3-потоки семейства ЕМГ/hostingradio и пр.).
     * Если какая-то ссылка со временем перестанет отвечать — достаточно поправить
     * URL здесь, имена менять не нужно.
     */
    public static final Station[] STATIONS = {
            new Station("Европа Плюс",   "https://europaplus.hostingradio.ru:8014/europaplus320.mp3"),
            new Station("Авто Радио",    "https://live.humorfm.by:8443/avtoradio"),
            new Station("Новое радио",   "http://emgspb.hostingradio.ru/novoespb128.mp3"),
            new Station("Маруся FM",     "https://listen.vdfm.ru:8000/marusya"),
            new Station("Ретро FM",      "https://retroserver.streamr.ru:8043/retro256.mp3"),
            new Station("Дорожное радио", "https://dorognoe.hostingradio.ru/dorognoe"),
            new Station("Наше радио",    "https://nashe1.hostingradio.ru/nashe-128.mp3"),
            new Station("Русское радио", "https://rusradio.hostingradio.ru/rusradio128.mp3"),
            new Station("DFM",           "https://dfm.hostingradio.ru/dfm128.mp3"),
            new Station("Максимум",      "https://maximum.hostingradio.ru/maximum128.mp3"),
    };

    private static final RadioPlayer INSTANCE = new RadioPlayer();

    /** Идентификатор текущей сессии воспроизведения: старый поток видит смену и выходит. */
    private final AtomicInteger session = new AtomicInteger();

    private volatile Station current;
    private volatile boolean playing;       // поток реально отдаёт звук
    private volatile boolean workerRunning;  // демон-поток станции жив (вкл. буфер/реконнект)
    private volatile float volume = 0.6f;    // 0..1
    private volatile float level;            // сглаженная громкость сигнала 0..1
    private volatile SourceDataLine line;

    // ── Спектр (FFT) для визуализации ──
    public static final int BANDS = 28;
    private static final int FFT_N = 1024;
    private final float[] ring = new float[FFT_N];
    private int ringPos;
    private int sampleRate = 44100;
    private final float[] bandsWork = new float[BANDS];
    private volatile float[] bands = new float[BANDS];

    private RadioPlayer() {}

    public static RadioPlayer get() {
        return INSTANCE;
    }

    public static String[] stationNames() {
        String[] names = new String[STATIONS.length];
        for (int i = 0; i < STATIONS.length; i++) names[i] = STATIONS[i].name();
        return names;
    }

    public static Station byName(String name) {
        if (name == null) return STATIONS[0];
        for (Station s : STATIONS) if (s.name().equals(name)) return s;
        return STATIONS[0];
    }

    public Station current()      { return current; }
    public boolean isPlaying()    { return playing; }
    public float level()          { return level; }

    /** Уровни частотных полос 0..1 (низы → верха). Копия, безопасно читать из рендера. */
    public float[] bands()        { return bands; }

    /** Станция выбрана, но звук ещё не пошёл (идёт подключение/буферизация). */
    public boolean connecting()   { return current != null && !playing; }

    /** Запустить станцию. Повторный вызов той же станции игнорируется. */
    public synchronized void play(Station station) {
        if (station == null) return;
        // Та же станция уже выбрана и её поток жив (включая буферизацию) — ничего не делаем.
        if (current != null && station.url().equals(current.url()) && workerRunning) return;

        final int id = session.incrementAndGet();
        current = station;
        playing = false;
        workerRunning = true;
        level = 0f;

        Thread t = new Thread(() -> {
            try {
                stream(station, id);
            } finally {
                if (id == session.get()) workerRunning = false;
            }
        }, "vesence-radio");
        t.setDaemon(true);
        t.start();
    }

    /** Полностью остановить воспроизведение. */
    public synchronized void stop() {
        session.incrementAndGet();
        current = null;
        playing = false;
        workerRunning = false;
        level = 0f;
        java.util.Arrays.fill(bandsWork, 0f);
        bands = new float[BANDS];
        closeLine();
    }

    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        applyGain(line);
    }

    // ── декодирование потока ──────────────────────────────────────────────────

    private void stream(Station station, int id) {
        int attempts = 0;
        while (id == session.get()) {
            try {
                decodeOnce(station, id);
                attempts = 0; // штатное завершение кадров — сразу переподключаемся
            } catch (Exception ignored) {
                // Обрыв сети/формата: короткая пауза и попытка снова, пока станция актуальна.
            }
            if (id != session.get()) break;
            attempts++;
            try {
                Thread.sleep(Math.min(5000L, 500L * attempts));
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void decodeOnce(Station station, int id) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(station.url()).toURL().openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(10000);
        // Нейтральный User-Agent: ничего личного наружу не отправляем.
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (radio)");
        conn.setRequestProperty("Icy-MetaData", "0"); // без ICY-метаданных — чистый MP3

        SourceDataLine local = null;
        try (InputStream raw = conn.getInputStream();
             InputStream in = new BufferedInputStream(raw, 8192)) {

            Bitstream bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();

            while (id == session.get()) {
                Header header = bitstream.readFrame();
                if (header == null) break; // поток кончился — выйдем, stream() переподключит

                SampleBuffer buffer = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                short[] pcm = buffer.getBuffer();
                int len = buffer.getBufferLength();
                int channels = buffer.getChannelCount();
                int rate = buffer.getSampleFrequency();

                if (local == null) {
                    local = openLine(rate, channels);
                    if (local == null) return;
                    line = local;
                    applyGain(local);
                    local.start();
                    playing = true;
                }

                writePcm(local, pcm, len);
                updateLevel(pcm, len);
                sampleRate = rate;
                updateSpectrum(pcm, len, channels);
                bitstream.closeFrame();
            }
            bitstream.close();
        } finally {
            if (local != null && line == local) {
                playing = false;
            }
            if (local != null) {
                try { local.drain(); } catch (Exception ignored) {}
                try { local.close(); } catch (Exception ignored) {}
            }
            try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private SourceDataLine openLine(int rate, int channels) {
        try {
            AudioFormat format = new AudioFormat(rate, 16, channels, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
            l.open(format, 16384);
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writePcm(SourceDataLine line, short[] pcm, int len) {
        byte[] bytes = new byte[len * 2];
        for (int i = 0; i < len; i++) {
            short s = pcm[i];
            bytes[i * 2] = (byte) (s & 0xff);
            bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        line.write(bytes, 0, bytes.length);
    }

    private void updateSpectrum(short[] pcm, int len, int channels) {
        int ch = Math.max(1, channels);
        for (int i = 0; i + ch - 1 < len; i += ch) {
            float m = 0f;
            for (int c = 0; c < ch; c++) m += pcm[i + c];
            ring[ringPos] = m / (ch * 32768f);
            ringPos = (ringPos + 1) & (FFT_N - 1);
        }

        float[] re = new float[FFT_N];
        float[] im = new float[FFT_N];
        for (int i = 0; i < FFT_N; i++) {
            float w = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * i / (FFT_N - 1)); // Ханн
            re[i] = ring[(ringPos + i) & (FFT_N - 1)] * w;
        }
        fft(re, im);

        double fMin = 50, fMax = Math.min(16000, sampleRate / 2.0);
        double binHz = sampleRate / (double) FFT_N;
        for (int b = 0; b < BANDS; b++) {
            double f0 = fMin * Math.pow(fMax / fMin, b / (double) BANDS);
            double f1 = fMin * Math.pow(fMax / fMin, (b + 1) / (double) BANDS);
            int k0 = Math.max(1, (int) Math.floor(f0 / binHz));
            int k1 = Math.max(k0 + 1, (int) Math.ceil(f1 / binHz));
            k1 = Math.min(k1, FFT_N / 2);
            double peak = 0;
            for (int k = k0; k < k1; k++) {
                double mag = Math.hypot(re[k], im[k]);
                if (mag > peak) peak = mag;
            }
            // dB → 0..1, плюс лёгкий подъём верхов (у них энергии меньше).
            double db = 20 * Math.log10(peak / (FFT_N / 4.0) + 1e-9) + b * 0.6;
            float v = (float) Math.max(0, Math.min(1, (db + 60) / 55));
            float cur = bandsWork[b];
            bandsWork[b] = v > cur ? cur + (v - cur) * 0.6f : cur + (v - cur) * 0.15f;
        }
        bands = bandsWork.clone();
    }

    /** Итеративный радикс-2 БПФ на месте. */
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1f, ci = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, bb = i + k + len / 2;
                    float xr = re[bb] * cr - im[bb] * ci;
                    float xi = re[bb] * ci + im[bb] * cr;
                    re[bb] = re[a] - xr; im[bb] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    float ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    private void updateLevel(short[] pcm, int len) {
        if (len <= 0) return;
        long sum = 0;
        for (int i = 0; i < len; i++) {
            int v = pcm[i];
            sum += (long) v * v;
        }
        double rms = Math.sqrt((double) sum / len) / 32768.0;
        float target = (float) Math.min(1.0, rms * 2.2); // чуть усиливаем для живости
        level += (target - level) * 0.35f;               // сглаживание
    }

    private void applyGain(SourceDataLine l) {
        if (l == null) return;
        try {
            if (l.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
                float v = volume;
                float db;
                if (v <= 0.0001f) {
                    db = gain.getMinimum();
                } else {
                    db = (float) (20.0 * Math.log10(v));
                    db = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db));
                }
                gain.setValue(db);
            }
        } catch (Exception ignored) {
        }
    }

    private void closeLine() {
        SourceDataLine l = line;
        line = null;
        if (l != null) {
            try { l.stop(); } catch (Exception ignored) {}
            try { l.close(); } catch (Exception ignored) {}
        }
    }
}
