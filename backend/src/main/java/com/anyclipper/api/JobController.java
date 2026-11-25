package com.anyclipper.api;

import com.anyclipper.job.Clip;
import com.anyclipper.job.Job;
import com.anyclipper.job.JobEventService;
import com.anyclipper.job.JobService;
import com.anyclipper.job.JobService.JobSettings;
import com.anyclipper.job.JobStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST API for jobs (spec, Section 4): create from an upload or a link, list, show, delete, and the
 * live progress stream. The request and response records are kept here, next to the endpoints.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    /** Body of POST /api/jobs when the source is a link (YouTube, Google Drive, ...). */
    public record CreateLinkJobRequest(String url, JobSettings settings) {
    }

    /** A job as the frontend sees it. The transcript is left out on purpose: it can be megabytes. */
    public record JobView(long id, JobStatus status, int progressPercent, String statusMessage,
                          String errorMessage, Job.SourceType sourceType, String sourceUrl, String title,
                          Double durationSeconds, JobSettings settings, Instant createdAt, Instant finishedAt) {

        static JobView of(Job job) {
            return new JobView(job.getId(), job.getStatus(), job.getProgressPercent(), job.getStatusMessage(),
                    job.getErrorMessage(), job.getSourceType(), job.getSourceUrl(), job.getTitle(),
                    job.getDurationSeconds(),
                    new JobSettings(job.getClipCount(), job.getMinClipSeconds(), job.getMaxClipSeconds(),
                            job.getCaptionPreset()),
                    job.getCreatedAt(), job.getFinishedAt());
        }
    }

    /** A clip as the frontend sees it. */
    public record ClipView(long id, Clip.Status status, double startSeconds, double endSeconds, int score,
                           String title, List<String> altTitles, String hook, String description,
                           List<String> hashtags, List<String> keywords, String reason, String captionPreset,
                           String errorMessage) {

        static ClipView of(Clip clip) {
            return new ClipView(clip.getId(), clip.getStatus(), clip.getStartSeconds(), clip.getEndSeconds(),
                    clip.getScore(), clip.getTitle(), clip.getAltTitles(), clip.getHook(), clip.getDescription(),
                    clip.getHashtags(), clip.getKeywords(), clip.getReason(), clip.getCaptionPreset(),
                    clip.getErrorMessage());
        }
    }

    /** Response of GET /api/jobs/{id}: the job and its clips (no clips until M3). */
    public record JobDetails(JobView job, List<ClipView> clips) {
    }

    private final JobService jobService;
    private final JobEventService jobEventService;

    public JobController(JobService jobService, JobEventService jobEventService) {
        this.jobService = jobService;
        this.jobEventService = jobEventService;
    }

    /**
     * Upload variant. The "settings" part must be sent as JSON (the frontend sends it as a Blob with
     * type application/json). The file arrives already streamed to disk; see JobService.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JobView createFromUpload(@RequestPart("file") MultipartFile file,
                                    @RequestPart("settings") JobSettings settings) {
        return JobView.of(jobService.createFromUpload(file, settings));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public JobView createFromLink(@RequestBody CreateLinkJobRequest request) {
        return JobView.of(jobService.createFromLink(request.url(), request.settings()));
    }

    @GetMapping
    public List<JobView> listJobs() {
        return jobService.listJobs().stream().map(JobView::of).toList();
    }

    @GetMapping("/{id}")
    public JobDetails getJob(@PathVariable long id) {
        Job job = jobService.getJob(id);
        List<ClipView> clips = jobService.getClips(id).stream().map(ClipView::of).toList();
        return new JobDetails(JobView.of(job), clips);
    }

    /** Live progress as Server-Sent Events, named "progress"; see JobEventService. */
    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long id) {
        jobService.getJob(id); // 404 for an unknown job
        return jobEventService.subscribe(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteJob(@PathVariable long id) {
        jobService.deleteJob(id);
        return ResponseEntity.noContent().build();
    }
}
