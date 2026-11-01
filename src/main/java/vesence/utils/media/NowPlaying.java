package vesence.utils.media;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * «Сейчас играет» из системного плеера Windows (SMTC / GlobalSystemMediaTransportControls).
 *
 * <p>Зачем PowerShell: WinRT-API доступен только через нативные биндинги, которых в
 * зависимостях клиента нет. Мы держим ОДИН долгоживущий процесс PowerShell, который
 * раз в {@code POLL_MS} опрашивает SMTC и печатает ASCII-строку; Java читает stdout
 * отдельным демон-потоком. Никакого блокирования рендер-потока: {@link #current()}
 * лишь читает volatile-снимок.
 *
 * <p>Формат строки процесса (разделитель — {@code |}):
 * {@code 1|status|positionSec|durationSec|b64(title)|b64(artist)|b64(album)|b64(app)}.
 * Строки/названия кодируются в Base64 (UTF-8), поэтому любые {@code |}, переводы
 * строк и кириллица безопасны.
 *
 * <p>Вне Windows сервис просто выключен ({@link #isSupported()} = false).
 */
@Environment(EnvType.CLIENT)
public final class NowPlaying {

    /** Снимок текущего трека. */
    public record Track(String title, String artist, String album, String app,
                        long positionMs, long durationMs, boolean playing, long pollNanos) {

        /**
         * Позиция воспроизведения «прямо сейчас»: последняя опрошенная + время,
         * прошедшее с опроса (SMTC отдаёт позицию только в момент запроса).
         */
        public long interpolatedPositionMs() {
            if (!playing) return positionMs;
            long delta = Math.max(0L, (System.nanoTime() - pollNanos) / 1_000_000L);
            long pos = positionMs + delta;
            if (durationMs > 0L) pos = Math.min(pos, durationMs);
            return Math.max(0L, pos);
        }
    }

    private static final boolean SUPPORTED = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");
    private static final long POLL_MS = 700L;
    private static final long RESTART_BACKOFF_MS = 20_000L;

    private static volatile Track track;
    private static volatile Process process;
    private static volatile boolean started;
    private static volatile boolean permanentlyUnavailable;
    private static volatile long lastStartAttemptMs;
    private static final AtomicBoolean STARTING = new AtomicBoolean();
    private static volatile boolean shutdownHookInstalled;

    private NowPlaying() {
    }

    public static boolean isSupported() {
        return SUPPORTED;
    }

    /** Текущий трек (включая паузу — караоке просто замирает) или {@code null}. */
    public static Track current() {
        if (!SUPPORTED || permanentlyUnavailable) return null;
        ensureStarted();
        Track t = track;
        return t != null && t.title() != null && !t.title().isBlank() ? t : null;
    }

    private static void ensureStarted() {
        if (started) return;
        long now = System.currentTimeMillis();
        if (now - lastStartAttemptMs < RESTART_BACKOFF_MS) return;
        if (!STARTING.compareAndSet(false, true)) return;
        lastStartAttemptMs = now;
        try {
            // Запуск процесса может занять сотни миллисекунд — не на рендер-потоке.
            Thread starter = new Thread(NowPlaying::startProcess, "vesence-smtc-start");
            starter.setDaemon(true);
            starter.start();
        } finally {
            STARTING.set(false);
        }
    }

    private static void startProcess() {
        if (started) return;
        try {
            String encoded = Base64.getEncoder().encodeToString(
                    SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-WindowStyle", "Hidden",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            process = p;
            started = true;
            installShutdownHook();

            Thread reader = new Thread(() -> readLoop(p), "vesence-smtc");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException | RuntimeException e) {
            started = false;
            track = null;
        }
    }

    private static void readLoop(Process p) {
        boolean readySeen = false;
        String lastLoggedTitle = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("READY")) {
                    readySeen = true;
                    System.out.println("[NowPlaying] SMTC poller ready");
                    continue;
                }
                Track parsed = parse(line);
                if (parsed != null) {
                    track = parsed;
                    if (!parsed.title().equals(lastLoggedTitle)) {
                        lastLoggedTitle = parsed.title();
                        System.out.println("[NowPlaying] track: " + parsed.title()
                                + " — " + parsed.artist());
                    }
                }
            }
        } catch (IOException ignored) {
            // процесс завершился — перезапустимся не раньше backoff
        } finally {
            started = false;
            track = null;
            // Скрипт ни разу не отрапортовал READY — WinRT/SMTC в системе не
            // доступен. Больше не спавним PowerShell каждые 20 секунд.
            if (!readySeen) {
                permanentlyUnavailable = true;
                System.out.println("[NowPlaying] SMTC unavailable (no READY), disabled");
            }
            Process cur = process;
            if (cur != null && cur.isAlive()) cur.destroy();
        }
    }

    private static Track parse(String line) {
        String[] f = line.split("\\|", -1);
        if (f.length < 8) return null;
        boolean active = "1".equals(f[0]);
        if (!active) return null;

        String status = f[1];
        long posMs = secToMs(f[2]);
        long durMs = secToMs(f[3]);
        String title = decode(f[4]);
        String artist = decode(f[5]);
        String album = decode(f[6]);
        String app = decode(f[7]);
        boolean playing = "Playing".equalsIgnoreCase(status);
        return new Track(title, artist, album, app, posMs, durMs, playing, System.nanoTime());
    }

    private static long secToMs(String s) {
        try {
            double v = Double.parseDouble(s.trim());
            if (!Double.isFinite(v) || v < 0) return 0L;
            return (long) (v * 1000.0);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String decode(String b64) {
        if (b64 == null || b64.isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static void installShutdownHook() {
        if (shutdownHookInstalled) return;
        shutdownHookInstalled = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Process p = process;
                if (p != null && p.isAlive()) p.destroy();
            }, "vesence-smtc-stop"));
        } catch (IllegalStateException ignored) {
        }
    }

    /**
     * Скрипт опроса. Вывод — только ASCII (Base64), иначе консольная кодировка
     * Windows испортила бы кириллицу. Выбираем сессию со статусом Playing,
     * иначе — текущую.
     */
    private static final String SCRIPT = """
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
              $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
            })[0]
            function Await($winRtTask, $resultType) {
              $asTask = $asTaskGeneric.MakeGenericMethod($resultType)
              $netTask = $asTask.Invoke($null, @($winRtTask))
              $netTask.Wait(-1) | Out-Null
              $netTask.Result
            }
            $mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
            $propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]
            try { $mgr = Await ($mgrType::RequestAsync()) $mgrType } catch { exit }
            if (-not $mgr) { exit }
            [Console]::Out.WriteLine("READY")
            [Console]::Out.Flush()
            function B64([string]$s) {
              if ([string]::IsNullOrEmpty($s)) { return "" }
              return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s))
            }
            while ($true) {
              $line = "0|||||||"
              try {
                $session = $mgr.GetCurrentSession()
                if ($session) {
                  # Пауза не «нет трека»: держим текущую сессию, но приоритет —
                  # реально играющей, если есть несколько.
                  $playing = $null
                  foreach ($cand in $mgr.GetSessions()) {
                    try {
                      if ($cand.GetPlaybackInfo().PlaybackStatus.ToString() -eq 'Playing') { $playing = $cand; break }
                    } catch { }
                  }
                  if ($playing) { $session = $playing }
                if ($session) {
                  $props = Await ($session.TryGetMediaPropertiesAsync()) $propsType
                  $tl = $session.GetTimelineProperties()
                  $status = $session.GetPlaybackInfo().PlaybackStatus.ToString()
                  $pos = [math]::Round($tl.Position.TotalSeconds, 3)
                  $end = [math]::Round($tl.EndTime.TotalSeconds, 3)
                  $app = $session.SourceAppUserModelId
                  $line = "1|$status|$pos|$end|$(B64 $props.Title)|$(B64 $props.Artist)|$(B64 $props.AlbumTitle)|$(B64 $app)"
                }
              } catch { $line = "0|||||||" }
              [Console]::Out.WriteLine($line)
              [Console]::Out.Flush()
              Start-Sleep -Milliseconds 700
            }
            """;
}