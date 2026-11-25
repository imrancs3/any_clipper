package com.anyclipper.job;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Records a job's status and progress, and pushes every change to the browser over Server-Sent Events.
 *
 * <p>The pipeline calls {@link #setStatus}, {@link #setProgress} and {@link #fail}; the job page listens
 * on GET /api/jobs/{id}/events. Status changes are saved and sent right away. Progress updates can
 * arrive many times per second during a download, so at most one per {@link #PROGRESS_INTERVAL} is
 * kept (saved and sent); the rest are dropped, which the user never notices.
 */
@Service
public class JobEventService {

    private static final Logger log = LoggerFactory.getLogger(JobEventService.class);

    /** Minimum time between two kept progress updates of the same job. */
    private static final Duration PROGRESS_INTERVAL = Duration.ofMillis(500);

    /**
     * Browsers reconnect by themselves when an SSE connection ends, and get the current state on
     * reconnect, so a moderate timeout just recycles idle connections.
     */
    private static final long EMITTER_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    /**
     * What the browser receives in each "progress" event.
     *
     * @param progressPercent progress of the current stage, 0 to 100
     */
    public record JobEvent(long jobId, JobStatus status, int progressPercent, String message,
                           String errorMessage) {

        static JobEvent of(Job job) {
            return new JobEvent(job.getId(), job.getStatus(), job.getProgressPercent(),
                    job.getStatusMessage(), job.getErrorMessage());
        }
    }

    private final JobRepository jobRepository;
    private final Map<Long, List<SseEmitter>> emittersByJob = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastProgressUpdate = new ConcurrentHashMap<>();

    public JobEventService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /**
     * Opens an SSE stream for one job. The current state is sent at once, so a page opened late (or a
     * reconnect after a dropped connection) is never blank. A finished job's stream closes right after.
     */
    public SseEmitter subscribe(long jobId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        List<SseEmitter> emitters = emittersByJob.computeIfAbsent(jobId, id -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));

        // Read the state only after the emitter is registered, so a change that happens in between
        // is either in this first event or published to the emitter afterwards; never lost.
        Job job = load(jobId);
        send(emitter, JobEvent.of(job));
        if (job.getStatus().isFinished()) {
            emitter.complete();
        }
        return emitter;
    }

    /** Moves a job to a new stage, resets its progress, saves it and notifies the browser. */
    public void setStatus(long jobId, JobStatus status, String message) {
        Job job = load(jobId);
        job.setStatus(status);
        job.setProgressPercent(status == JobStatus.DONE ? 100 : 0);
        job.setStatusMessage(message);
        publish(jobRepository.save(job));
    }

    /** Updates the progress of the current stage; see the class comment for how often it is kept. */
    public void setProgress(long jobId, int percent, String message) {
        Instant now = Instant.now();
        Instant last = lastProgressUpdate.get(jobId);
        if (last != null && Duration.between(last, now).compareTo(PROGRESS_INTERVAL) < 0) {
            return;
        }
        lastProgressUpdate.put(jobId, now);

        Job job = load(jobId);
        job.setProgressPercent(Math.clamp(percent, 0, 100));
        job.setStatusMessage(message);
        publish(jobRepository.save(job));
    }

    /** Marks a job FAILED with a message for the user, saves it and notifies the browser. */
    public void fail(long jobId, String errorMessage) {
        Job job = load(jobId);
        job.setStatus(JobStatus.FAILED);
        job.setStatusMessage("Failed");
        job.setErrorMessage(errorMessage);
        publish(jobRepository.save(job));
    }

    /** Closes the streams of a deleted job. */
    public void forget(long jobId) {
        lastProgressUpdate.remove(jobId);
        List<SseEmitter> emitters = emittersByJob.remove(jobId);
        if (emitters != null) {
            emitters.forEach(SseEmitter::complete);
        }
    }

    /**
     * Sends a comment line to every open stream every 20 seconds. Without it, proxies and tunnels
     * (such as ngrok) may close a connection that is quiet during a long stage.
     */
    @Scheduled(fixedRate = 20_000)
    void sendHeartbeat() {
        emittersByJob.values().forEach(emitters -> emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }));
    }

    private Job load(long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job " + jobId + " no longer exists"));
    }

    private void publish(Job job) {
        JobEvent event = JobEvent.of(job);
        List<SseEmitter> emitters = emittersByJob.get(job.getId());
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            send(emitter, event);
            if (job.getStatus().isFinished()) {
                emitter.complete();
            }
        }
        if (job.getStatus().isFinished()) {
            emittersByJob.remove(job.getId());
            lastProgressUpdate.remove(job.getId());
        }
    }

    private void send(SseEmitter emitter, JobEvent event) {
        try {
            emitter.send(SseEmitter.event().name("progress").data(event));
        } catch (IOException | IllegalStateException e) {
            // The browser went away; the emitter's onError/onCompletion callbacks remove it.
            log.debug("Could not send SSE event for job {}: {}", event.jobId(), e.getMessage());
        }
    }
}
