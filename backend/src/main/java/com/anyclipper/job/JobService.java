package com.anyclipper.job;

import com.anyclipper.common.ClipperException;
import com.anyclipper.config.ClipperProperties;
import com.anyclipper.config.StartupChecks;
import com.anyclipper.pipeline.DownloadService;
import com.anyclipper.pipeline.PipelineService;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Creates, lists and deletes jobs, and knows where each job's files live.
 *
 * <p>This is step 1 (ingest) on the request side: an upload is moved into the job folder here, a link
 * is checked here and then downloaded by the pipeline. Every new job is handed to
 * {@link PipelineService}, which runs the stages in the background.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    /** Caption styles the render step offers (spec, Step 6). */
    public static final Set<String> CAPTION_PRESETS = Set.of("bold-yellow", "clean-white", "boxed");

    /** File extensions kept for uploads; anything else is saved as .mp4 (ffmpeg reads the content). */
    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{2,5}");

    /**
     * Per-job settings chosen on the Home page.
     *
     * @param clipCount how many clips to look for
     * @param minClipSeconds shortest allowed clip
     * @param maxClipSeconds longest allowed clip
     * @param captionPreset default caption style for the job's clips
     */
    public record JobSettings(int clipCount, int minClipSeconds, int maxClipSeconds, String captionPreset) {
    }

    private final JobRepository jobRepository;
    private final ClipRepository clipRepository;
    private final JobEventService jobEventService;
    private final PipelineService pipelineService;
    private final StartupChecks startupChecks;
    private final ClipperProperties properties;

    public JobService(JobRepository jobRepository, ClipRepository clipRepository,
                      JobEventService jobEventService, PipelineService pipelineService,
                      StartupChecks startupChecks, ClipperProperties properties) {
        this.jobRepository = jobRepository;
        this.clipRepository = clipRepository;
        this.jobEventService = jobEventService;
        this.pipelineService = pipelineService;
        this.startupChecks = startupChecks;
        this.properties = properties;
    }

    /**
     * Creates a job from an uploaded file. By the time this runs, Tomcat has already streamed the whole
     * upload to a temp file in data/upload-tmp (never into memory). {@code transferTo(File)} with an
     * absolute path makes Tomcat rename that temp file into the job folder, so the video is not copied.
     */
    public Job createFromUpload(MultipartFile file, JobSettings settings) {
        validate(settings);
        if (file.isEmpty()) {
            throw new ClipperException(HttpStatus.BAD_REQUEST, "The uploaded file is empty");
        }

        String originalName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        Job job = newJob(Job.SourceType.UPLOAD, settings);
        job.setOriginalFileName(originalName);
        job.setTitle(originalName);
        job.setSourceFileName("source." + extensionOf(originalName));
        job = jobRepository.save(job);

        try {
            Path target = properties.jobDir(job.getId()).resolve(job.getSourceFileName()).toAbsolutePath();
            Files.createDirectories(target.getParent());
            file.transferTo(target.toFile());
        } catch (IOException e) {
            deleteFiles(job.getId());
            jobRepository.delete(job);
            throw new ClipperException("Could not save the uploaded file: " + e.getMessage(), e);
        }

        log.info("Job {} created from upload '{}' ({} MB)", job.getId(), originalName, file.getSize() / 1_000_000);
        pipelineService.start(job.getId());
        return job;
    }

    /**
     * Creates a job from a link. Google Drive file links are downloaded directly; every other link goes
     * to yt-dlp, which supports YouTube and many other sites.
     */
    public Job createFromLink(String url, JobSettings settings) {
        validate(settings);
        String link = url == null ? "" : url.trim();
        checkIsWebLink(link);

        Job.SourceType sourceType;
        if (DownloadService.isGoogleDriveLink(link)) {
            if (DownloadService.googleDriveFileId(link).isEmpty()) {
                throw new ClipperException(HttpStatus.BAD_REQUEST, "This Google Drive link does not point to a "
                        + "single file. Open the video in Drive, choose Share > Copy link, and paste that link.");
            }
            sourceType = Job.SourceType.GOOGLE_DRIVE;
        } else {
            if (!startupChecks.isYtDlpAvailable()) {
                throw new ClipperException(HttpStatus.BAD_REQUEST,
                        "yt-dlp is not installed (or turned off), so only uploads and Google Drive links work. "
                                + "Install yt-dlp and restart Clipper to use YouTube links.");
            }
            sourceType = Job.SourceType.LINK;
        }

        Job job = newJob(sourceType, settings);
        job.setSourceUrl(link);
        job.setTitle(link);
        job = jobRepository.save(job);
        log.info("Job {} created from {} link {}", job.getId(), sourceType, link);
        pipelineService.start(job.getId());
        return job;
    }

    public List<Job> listJobs() {
        return jobRepository.findAllByOrderByCreatedAtDesc();
    }

    public Job getJob(long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new ClipperException(HttpStatus.NOT_FOUND, "Job " + jobId + " does not exist"));
    }

    public List<Clip> getClips(long jobId) {
        return clipRepository.findByJobIdOrderByScoreDesc(jobId);
    }

    /** Deletes a finished job with its clips and its whole folder. Running jobs cannot be deleted yet. */
    public void deleteJob(long jobId) {
        Job job = getJob(jobId);
        if (!job.getStatus().isFinished()) {
            throw new ClipperException(HttpStatus.CONFLICT,
                    "The job is still running. Wait until it is done or failed, then delete it.");
        }
        clipRepository.deleteByJobId(jobId);
        jobRepository.delete(job);
        jobEventService.forget(jobId);
        deleteFiles(jobId);
        log.info("Job {} deleted", jobId);
    }

    private Job newJob(Job.SourceType sourceType, JobSettings settings) {
        Job job = new Job();
        job.setSourceType(sourceType);
        job.setClipCount(settings.clipCount());
        job.setMinClipSeconds(settings.minClipSeconds());
        job.setMaxClipSeconds(settings.maxClipSeconds());
        job.setCaptionPreset(settings.captionPreset());
        job.setStatusMessage("Waiting to start");
        return job;
    }

    // Limits keep the later stages sensible: Gemini is asked for 1-30 clips of 10 s to 3 min.
    private void validate(JobSettings settings) {
        if (settings == null) {
            throw new ClipperException(HttpStatus.BAD_REQUEST, "Settings are missing");
        }
        if (settings.clipCount() < 1 || settings.clipCount() > 30) {
            throw new ClipperException(HttpStatus.BAD_REQUEST, "Clip count must be between 1 and 30");
        }
        if (settings.minClipSeconds() < 10 || settings.maxClipSeconds() > 180
                || settings.minClipSeconds() >= settings.maxClipSeconds()) {
            throw new ClipperException(HttpStatus.BAD_REQUEST,
                    "Clip length must be at least 10 s, at most 180 s, and the minimum must be below the maximum");
        }
        if (!CAPTION_PRESETS.contains(settings.captionPreset())) {
            throw new ClipperException(HttpStatus.BAD_REQUEST, "Unknown caption preset: " + settings.captionPreset());
        }
    }

    // Only http(s) links are accepted, so a "link" can never be a local file path or another scheme.
    private void checkIsWebLink(String link) {
        try {
            URI uri = new URI(link);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null) {
                return;
            }
        } catch (URISyntaxException e) {
            // Falls through to the error below.
        }
        throw new ClipperException(HttpStatus.BAD_REQUEST, "Please paste a full web link starting with https://");
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SAFE_EXTENSION.matcher(extension).matches() ? extension : "mp4";
    }

    private void deleteFiles(long jobId) {
        try {
            FileSystemUtils.deleteRecursively(properties.jobDir(jobId));
        } catch (IOException e) {
            log.warn("Could not delete the folder of job {}: {}", jobId, e.getMessage());
        }
    }
}
