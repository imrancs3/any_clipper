package com.anyclipper.pipeline;

import com.anyclipper.common.ClipperException;
import com.anyclipper.common.ProcessRunner;
import com.anyclipper.config.ClipperProperties;
import com.anyclipper.job.Job;
import com.anyclipper.job.JobEventService;
import com.anyclipper.job.JobRepository;
import com.anyclipper.job.JobStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs a job's pipeline stages in order, in the background, one job at a time.
 *
 * <p>M1 has one stage, ingest: download the link (uploads are already in place), then check with
 * ffprobe that the file is a readable video and record its length. The job then ends as DONE.
 * Later milestones add audio extraction, transcription, clip selection, cutting and rendering here.
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    /**
     * Only one job runs at a time, so a low-end PC never downloads or processes two videos at once.
     * Fair, so waiting jobs start in the order they were created.
     */
    private final Semaphore pipelineSlot = new Semaphore(1, true);

    private final JobRepository jobRepository;
    private final JobEventService jobEventService;
    private final DownloadService downloadService;
    private final ProcessRunner processRunner;
    private final ClipperProperties properties;

    public PipelineService(JobRepository jobRepository, JobEventService jobEventService,
                           DownloadService downloadService, ProcessRunner processRunner,
                           ClipperProperties properties) {
        this.jobRepository = jobRepository;
        this.jobEventService = jobEventService;
        this.downloadService = downloadService;
        this.processRunner = processRunner;
        this.properties = properties;
    }

    /**
     * Runs the whole pipeline for one job on its own virtual thread. The job stays QUEUED while it
     * waits for the pipeline slot. Any failure marks the job FAILED with a message for the user.
     */
    @Async
    public void start(long jobId) {
        try {
            pipelineSlot.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            jobEventService.fail(jobId, "Stopped while waiting to start");
            return;
        }
        try {
            ingest(jobId);
            jobEventService.setStatus(jobId, JobStatus.DONE, "Video saved and checked");
        } catch (ClipperException e) {
            log.warn("Job {} failed: {}", jobId, e.getMessage());
            jobEventService.fail(jobId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Job {} failed unexpectedly", jobId, e);
            jobEventService.fail(jobId, "Unexpected error: " + e.getMessage());
        } finally {
            pipelineSlot.release();
        }
    }

    /** Step 1: get the source video into data/jobs/{id}/ and check it. */
    private void ingest(long jobId) {
        long started = System.currentTimeMillis();
        Job job = jobRepository.findById(jobId).orElseThrow();
        Path jobDir = properties.jobDir(jobId);
        log.info("Job {}: ingest started ({})", jobId, job.getSourceType());

        if (job.getSourceType() != Job.SourceType.UPLOAD) {
            jobEventService.setStatus(jobId, JobStatus.DOWNLOADING, "Starting download");
            DownloadService.DownloadResult download = job.getSourceType() == Job.SourceType.GOOGLE_DRIVE
                    ? downloadService.downloadGoogleDrive(jobId, job.getSourceUrl(), jobDir)
                    : downloadService.downloadWithYtDlp(jobId, job.getSourceUrl(), jobDir);
            job = jobRepository.findById(jobId).orElseThrow();
            job.setSourceFileName(download.sourceFileName());
            if (download.title() != null && !download.title().isBlank()) {
                job.setTitle(download.title());
            }
            jobRepository.save(job);
        }

        Path source = jobDir.resolve(job.getSourceFileName());
        double duration = probeDurationSeconds(source);
        job = jobRepository.findById(jobId).orElseThrow();
        job.setDurationSeconds(duration);
        jobRepository.save(job);
        log.info("Job {}: ingest finished in {} s, video is {} s long",
                jobId, (System.currentTimeMillis() - started) / 1000, Math.round(duration));
    }

    /**
     * Reads the video length with ffprobe. This also proves the file really is a video with a picture,
     * so a broken upload or an HTML error page fails here instead of in a later, slower stage.
     */
    private double probeDurationSeconds(Path source) {
        if (!Files.isRegularFile(source)) {
            throw new ClipperException("The source video is missing: " + source.getFileName());
        }
        // Prints "video" for the first video stream (nothing if there is none), then the duration.
        List<String> output = new ArrayList<>();
        ProcessRunner.Result result = processRunner.run(List.of(
                properties.tools().ffprobe(), "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=codec_type:format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                source.toAbsolutePath().toString()), Duration.ofMinutes(2), output::add);
        boolean hasVideo = output.stream().anyMatch(line -> line.trim().equals("video"));
        double duration = output.stream().map(String::trim).filter(line -> line.matches("[0-9.]+"))
                .mapToDouble(Double::parseDouble).findFirst().orElse(0);
        if (result.succeeded() && hasVideo && duration > 0) {
            return duration;
        }
        throw new ClipperException("This file is not a video ffmpeg can read."
                + (result.lastLines().isEmpty() ? "" : " ffprobe said: " + result.lastLinesText()));
    }
}
