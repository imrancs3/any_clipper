package com.anyclipper.config;

import com.anyclipper.common.ClipperException;
import com.anyclipper.common.ProcessRunner;
import com.anyclipper.job.Job;
import com.anyclipper.job.JobRepository;
import com.anyclipper.job.JobStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Checks run once when Clipper starts, before any job is processed (spec, Section 5).
 *
 * <ol>
 *   <li>Stops startup if APP_ACCESS_TOKEN is not set, so the app is never open without protection.</li>
 *   <li>Creates the data folders and removes uploads left half-written by a previous run.</li>
 *   <li>Checks that ffmpeg, ffprobe and (if enabled) yt-dlp can be run, with a clear log line if not.</li>
 *   <li>Marks jobs that were still running (or waiting) as FAILED, because their work stopped with the
 *       previous process and the in-memory queue is gone.</li>
 * </ol>
 */
@Component
public class StartupChecks implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);
    private static final Duration VERSION_CHECK_TIMEOUT = Duration.ofSeconds(10);

    private final ClipperProperties properties;
    private final ProcessRunner processRunner;
    private final JobRepository jobRepository;
    private final Environment environment;

    /** Set by the tool check; read by request threads when a link job is created. */
    private volatile boolean ytDlpAvailable;

    public StartupChecks(ClipperProperties properties, ProcessRunner processRunner, JobRepository jobRepository,
                         Environment environment) {
        this.properties = properties;
        this.processRunner = processRunner;
        this.jobRepository = jobRepository;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        checkAccessToken();

        Files.createDirectories(properties.jobsDir());
        Files.createDirectories(properties.uploadTempDir());
        deleteLeftoverUploads();

        boolean ffmpegAvailable = isToolAvailable(properties.tools().ffmpeg(), "-version",
                "Install FFmpeg (Windows: 'winget install Gyan.FFmpeg', Linux: 'sudo apt install ffmpeg').");
        boolean ffprobeAvailable = isToolAvailable(properties.tools().ffprobe(), "-version",
                "ffprobe comes with FFmpeg; make sure the FFmpeg 'bin' folder is on PATH.");
        if (properties.tools().ytDlpEnabled()) {
            ytDlpAvailable = isToolAvailable(properties.tools().ytDlp(), "--version",
                    "Install yt-dlp (Windows: 'winget install yt-dlp.yt-dlp', Linux: 'pipx install yt-dlp'). "
                            + "Uploads and Google Drive links still work without it.");
        } else {
            log.info("yt-dlp is turned off (clipper.tools.yt-dlp-enabled=false); YouTube links are disabled");
        }
        if (ffmpegAvailable && ffprobeAvailable) {
            log.info("Tool check passed: ffmpeg and ffprobe found{}", ytDlpAvailable ? ", yt-dlp found" : "");
        }

        markInterruptedJobsFailed();
        log.info("Clipper is ready: open http://localhost:{} in your browser",
                environment.getProperty("local.server.port", "8080"));
    }

    /**
     * Stops startup without a token, and logs where the token came from (never the token itself),
     * because an old environment variable silently overriding .env is an easy mistake to make.
     */
    private void checkAccessToken() {
        String token = AccessTokenFilter.normalize(properties.accessToken());
        Path envFile = Path.of("..", ".env").toAbsolutePath().normalize();
        if (token.isEmpty()) {
            throw new IllegalStateException("APP_ACCESS_TOKEN is not set. Put APP_ACCESS_TOKEN=... in "
                    + envFile + " (copy .env.example), start Clipper from the backend folder, and try again.");
        }
        String source = System.getenv("APP_ACCESS_TOKEN") != null
                ? "the APP_ACCESS_TOKEN environment variable (this overrides .env)"
                : Files.exists(envFile) ? envFile.toString() : "the configuration";
        log.info("Access token loaded from {} ({} characters)", source, token.length());
    }

    /** False when yt-dlp is missing or turned off; link jobs other than Google Drive are then refused. */
    public boolean isYtDlpAvailable() {
        return ytDlpAvailable;
    }

    private boolean isToolAvailable(String tool, String versionFlag, String installHint) {
        try {
            ProcessRunner.Result result = processRunner.run(List.of(tool, versionFlag), VERSION_CHECK_TIMEOUT);
            if (result.succeeded()) {
                return true;
            }
            log.error("'{} {}' failed (exit code {}). {}", tool, versionFlag, result.exitCode(), installHint);
        } catch (ClipperException e) {
            log.error("'{}' was not found on PATH. {}", tool, installHint);
        }
        return false;
    }

    private void deleteLeftoverUploads() throws IOException {
        try (Stream<Path> files = Files.list(properties.uploadTempDir())) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private void markInterruptedJobsFailed() {
        List<Job> interrupted = jobRepository.findByStatusNotIn(List.of(JobStatus.DONE, JobStatus.FAILED));
        for (Job job : interrupted) {
            job.setStatus(JobStatus.FAILED);
            job.setStatusMessage("Failed");
            job.setErrorMessage("Interrupted by restart");
            jobRepository.save(job);
        }
        if (!interrupted.isEmpty()) {
            log.warn("Marked {} interrupted job(s) as FAILED", interrupted.size());
        }
    }
}
