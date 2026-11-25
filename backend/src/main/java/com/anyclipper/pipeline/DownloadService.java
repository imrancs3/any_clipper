package com.anyclipper.pipeline;

import com.anyclipper.common.ClipperException;
import com.anyclipper.common.ProcessRunner;
import com.anyclipper.config.ClipperProperties;
import com.anyclipper.job.JobEventService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Pipeline step 1 for links: downloads the source video into the job folder.
 *
 * <p>Google Drive file links are streamed straight to disk with {@link RestClient} (Drive is not handled
 * well by yt-dlp for large files). All other links (YouTube and the other sites yt-dlp supports) go to
 * the yt-dlp command line tool, limited to {@code clipper.download.max-height} (1080p by default).
 * Both paths report live progress through {@link JobEventService} and never hold the video in memory.
 */
@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

    private static final Pattern DRIVE_FILE_PATH = Pattern.compile("/file/d/([A-Za-z0-9_-]{10,})");
    private static final Pattern DRIVE_ID_PARAM = Pattern.compile("[?&]id=([A-Za-z0-9_-]{10,})");
    private static final Pattern FILE_NAME_IN_HEADER = Pattern.compile("filename=\"?([^\";]+)\"?");
    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{2,5}");

    /** Lines yt-dlp prints because of the --print and --progress-template options below. */
    private static final String TITLE_PREFIX = "CLIPPER_TITLE:";
    private static final String FILE_PREFIX = "CLIPPER_FILE:";
    private static final String PROGRESS_PREFIX = "CLIPPER_PROGRESS:";

    private static final int COPY_BUFFER_BYTES = 1024 * 1024;

    /**
     * What a finished download produced.
     *
     * @param sourceFileName the file name inside the job folder, e.g. "source.mp4"
     * @param title the video title, or null when the source does not provide one
     */
    public record DownloadResult(String sourceFileName, String title) {
    }

    /**
     * One parsed yt-dlp progress line.
     *
     * @param percent progress of the file being downloaded, 0 to 100, or -1 when the size is unknown
     * @param speedBytesPerSecond current speed, or -1 when unknown
     * @param etaSeconds estimated seconds left, or -1 when unknown
     */
    record YtDlpProgress(int percent, double speedBytesPerSecond, long etaSeconds) {
    }

    private final ProcessRunner processRunner;
    private final JobEventService jobEventService;
    private final ClipperProperties properties;
    private final RestClient restClient;

    public DownloadService(ProcessRunner processRunner, JobEventService jobEventService,
                           ClipperProperties properties) {
        this.processRunner = processRunner;
        this.jobEventService = jobEventService;
        this.properties = properties;
        // Drive answers with redirects to its download host, so redirects must be followed.
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.restClient = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(httpClient)).build();
    }

    /** True for drive.google.com and docs.google.com links. */
    public static boolean isGoogleDriveLink(String url) {
        String host = hostOf(url);
        return host.equals("drive.google.com") || host.equals("docs.google.com")
                || host.equals("drive.usercontent.google.com");
    }

    /** The file id of a Drive file link (".../file/d/{id}/view" or "...?id={id}"); empty for folders. */
    public static Optional<String> googleDriveFileId(String url) {
        for (Pattern pattern : List.of(DRIVE_FILE_PATH, DRIVE_ID_PARAM)) {
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    /**
     * Downloads a public Google Drive file ("Anyone with the link") into the job folder.
     *
     * <p>The usercontent endpoint with {@code confirm=t} skips Drive's "can't scan this file for viruses"
     * page that large files get. If Drive still answers with an HTML page, the file is not shared
     * publicly (or its daily download quota is used up), and we say so instead of saving the page.
     */
    public DownloadResult downloadGoogleDrive(long jobId, String url, Path jobDir) {
        String fileId = googleDriveFileId(url)
                .orElseThrow(() -> new ClipperException("This Google Drive link does not point to a single file"));
        URI downloadUri = URI.create("https://drive.usercontent.google.com/download?id=" + fileId
                + "&export=download&confirm=t");
        Path partFile = jobDir.resolve("source.part");
        Instant deadline = Instant.now().plus(properties.download().timeout());

        AtomicReference<String> fileName = new AtomicReference<>();
        try {
            Files.createDirectories(jobDir);
            restClient.get().uri(downloadUri).exchange((request, response) -> {
                MediaType contentType = response.getHeaders().getContentType();
                if (!response.getStatusCode().is2xxSuccessful()
                        || (contentType != null && contentType.isCompatibleWith(MediaType.TEXT_HTML))) {
                    throw new ClipperException("Google Drive did not send the video. Make sure the file is shared "
                            + "as 'Anyone with the link', or try again later if Drive's download limit was reached.");
                }
                fileName.set(fileNameFromHeader(response.getHeaders().getFirst("Content-Disposition")));
                long totalBytes = response.getHeaders().getContentLength();
                try (InputStream body = response.getBody(); OutputStream out = Files.newOutputStream(partFile)) {
                    copyWithProgress(jobId, body, out, totalBytes, deadline);
                }
                return null;
            });
            String sourceFileName = "source." + extensionOf(fileName.get());
            Files.move(partFile, jobDir.resolve(sourceFileName), StandardCopyOption.REPLACE_EXISTING);
            return new DownloadResult(sourceFileName, fileName.get());
        } catch (IOException | RestClientException e) {
            throw new ClipperException("Google Drive download failed: " + e.getMessage(), e);
        } finally {
            deleteQuietly(partFile);
        }
    }

    /**
     * Downloads a YouTube (or other yt-dlp supported) link into the job folder as source.mp4.
     *
     * <p>Picks the best video up to the configured height plus the best audio, preferring mp4/m4a so
     * merging needs no re-encode, and remuxes anything else into mp4. The custom --print and
     * --progress-template lines give us the title, the final file and machine-readable progress.
     */
    public DownloadResult downloadWithYtDlp(long jobId, String url, Path jobDir) {
        int maxHeight = properties.download().maxHeight();
        ClipperProperties.Tools tools = properties.tools();

        List<String> command = new ArrayList<>(List.of(
                tools.ytDlp(),
                "--no-playlist",
                "--newline",
                "--progress",
                "--progress-template", "download:" + PROGRESS_PREFIX
                        + "%(progress.downloaded_bytes)s|%(progress.total_bytes)s"
                        + "|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s",
                "--print", "before_dl:" + TITLE_PREFIX + "%(title)s",
                "--print", "after_move:" + FILE_PREFIX + "%(filepath)s",
                // "<=?" also accepts formats whose height is unknown (e.g. a direct link to a video file).
                "-f", "bv*[height<=?" + maxHeight + "]+ba/b[height<=?" + maxHeight + "]",
                "-S", "ext:mp4:m4a",
                "--merge-output-format", "mp4",
                "--remux-video", "mp4",
                "-o", jobDir.toAbsolutePath().resolve("source.%(ext)s").toString()));
        if (!tools.ffmpeg().equals("ffmpeg")) {
            command.addAll(List.of("--ffmpeg-location", tools.ffmpeg()));
        }
        // "--" ends the options, so a pasted link can never be read as a yt-dlp option.
        command.addAll(List.of("--", url));

        AtomicReference<String> title = new AtomicReference<>();
        AtomicReference<String> finalFile = new AtomicReference<>();
        ProcessRunner.Result result = processRunner.run(command, properties.download().timeout(), line -> {
            if (line.startsWith(TITLE_PREFIX)) {
                title.set(line.substring(TITLE_PREFIX.length()).trim());
            } else if (line.startsWith(FILE_PREFIX)) {
                finalFile.set(line.substring(FILE_PREFIX.length()).trim());
            } else if (line.startsWith(PROGRESS_PREFIX)) {
                parseYtDlpProgress(line).ifPresent(progress -> reportProgress(jobId, progress));
            }
        });

        if (!result.succeeded() || finalFile.get() == null) {
            throw new ClipperException("yt-dlp could not download this link:\n" + errorLines(result));
        }
        String sourceFileName = Path.of(finalFile.get()).getFileName().toString();
        log.info("Job {}: yt-dlp saved {}", jobId, sourceFileName);
        return new DownloadResult(sourceFileName, title.get());
    }

    /**
     * Parses a line printed by our --progress-template, e.g.
     * {@code CLIPPER_PROGRESS:52428800|104857600|NA|3145728.0|16}. yt-dlp prints "NA" for unknown values,
     * and for some sites only the estimated total size is known.
     */
    static Optional<YtDlpProgress> parseYtDlpProgress(String line) {
        if (!line.startsWith(PROGRESS_PREFIX)) {
            return Optional.empty();
        }
        String[] parts = line.substring(PROGRESS_PREFIX.length()).split("\\|", -1);
        if (parts.length != 5) {
            return Optional.empty();
        }
        double downloaded = number(parts[0]);
        double total = number(parts[1]) > 0 ? number(parts[1]) : number(parts[2]);
        int percent = downloaded >= 0 && total > 0 ? (int) Math.min(100, downloaded * 100 / total) : -1;
        double eta = number(parts[4]);
        return Optional.of(new YtDlpProgress(percent, number(parts[3]), eta < 0 ? -1 : Math.round(eta)));
    }

    /** "Downloading 42% at 3.1 MiB/s, 2:10 left", leaving out what is unknown. */
    static String progressMessage(int percent, double speedBytesPerSecond, long etaSeconds) {
        StringBuilder message = new StringBuilder("Downloading");
        if (percent >= 0) {
            message.append(' ').append(percent).append('%');
        }
        if (speedBytesPerSecond > 0) {
            message.append(String.format(Locale.ROOT, " at %.1f MiB/s", speedBytesPerSecond / (1024 * 1024)));
        }
        if (etaSeconds >= 0) {
            message.append(String.format(Locale.ROOT, ", %d:%02d left", etaSeconds / 60, etaSeconds % 60));
        }
        return message.toString();
    }

    private void reportProgress(long jobId, YtDlpProgress progress) {
        jobEventService.setProgress(jobId, Math.max(progress.percent(), 0),
                progressMessage(progress.percent(), progress.speedBytesPerSecond(), progress.etaSeconds()));
    }

    // Copies in 1 MB chunks and reports progress; the speed is averaged over the whole download.
    private void copyWithProgress(long jobId, InputStream in, OutputStream out, long totalBytes, Instant deadline)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long copied = 0;
        Instant started = Instant.now();
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            copied += read;
            if (Instant.now().isAfter(deadline)) {
                throw new ClipperException("The download took longer than "
                        + properties.download().timeout().toMinutes() + " minutes and was stopped");
            }
            double seconds = Math.max(0.001, Duration.between(started, Instant.now()).toMillis() / 1000.0);
            double speed = copied / seconds;
            int percent = totalBytes > 0 ? (int) (copied * 100 / totalBytes) : -1;
            long eta = totalBytes > 0 ? Math.round((totalBytes - copied) / speed) : -1;
            jobEventService.setProgress(jobId, Math.max(percent, 0), progressMessage(percent, speed, eta));
        }
    }

    // yt-dlp's own error lines start with "ERROR:"; show those, or the last lines if there are none.
    private static String errorLines(ProcessRunner.Result result) {
        List<String> errors = result.lastLines().stream().filter(line -> line.startsWith("ERROR")).toList();
        return errors.isEmpty() ? result.lastLinesText() : String.join("\n", errors);
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String fileNameFromHeader(String contentDisposition) {
        if (contentDisposition == null) {
            return null;
        }
        Matcher matcher = FILE_NAME_IN_HEADER.matcher(contentDisposition);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "mp4";
        }
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SAFE_EXTENSION.matcher(extension).matches() ? extension : "mp4";
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", file, e.getMessage());
        }
    }
}
