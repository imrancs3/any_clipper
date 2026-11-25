package com.anyclipper.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;

/**
 * One source video and everything Clipper does with it: ingest, transcript, clip selection, renders.
 *
 * <p>The job's files live in data/jobs/{id}/ (see {@link com.anyclipper.config.ClipperProperties#jobDir}); no file paths are stored
 * here. Columns used by later milestones (language, transcript) already exist so the schema stays stable.
 */
@Entity
public class Job {

    /** Where the source video comes from. */
    public enum SourceType {
        /** A file uploaded from the browser. */
        UPLOAD,
        /** A public Google Drive file link, downloaded directly by Clipper. */
        GOOGLE_DRIVE,
        /** Any other link (YouTube and the other sites yt-dlp supports). */
        LINK
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobStatus status = JobStatus.QUEUED;

    /** Progress of the current stage, 0 to 100. */
    private int progressPercent;

    /** Short human-readable progress text, e.g. "Downloading 42% at 3.1 MiB/s". */
    @Column(length = 500)
    private String statusMessage;

    @Lob
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SourceType sourceType;

    /** The link the user pasted; null for uploads. */
    @Column(length = 2000)
    private String sourceUrl;

    /** The uploaded file's original name; null for links. */
    @Column(length = 500)
    private String originalFileName;

    /** File name of the source video inside the job folder, e.g. "source.mp4" or "source.mkv". */
    @Column(length = 100)
    private String sourceFileName;

    /** Display name: the file name, the video title or the link. */
    @Column(length = 500)
    private String title;

    /** Length of the source video in seconds, read with ffprobe after ingest. */
    private Double durationSeconds;

    private int clipCount;
    private int minClipSeconds;
    private int maxClipSeconds;

    @Column(length = 30)
    private String captionPreset;

    /** Spoken language detected during transcription (M2). */
    @Column(length = 20)
    private String language;

    /** Merged word-level transcript as JSON (M2); also saved as transcript.json. */
    @Lob
    private String transcriptJson;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant finishedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public JobStatus getStatus() {
        return status;
    }

    /** Sets the status; reaching DONE or FAILED also records the finish time. */
    public void setStatus(JobStatus status) {
        this.status = status;
        if (status.isFinished()) {
            finishedAt = Instant.now();
        }
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = progressPercent;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Double getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Double durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public int getClipCount() {
        return clipCount;
    }

    public void setClipCount(int clipCount) {
        this.clipCount = clipCount;
    }

    public int getMinClipSeconds() {
        return minClipSeconds;
    }

    public void setMinClipSeconds(int minClipSeconds) {
        this.minClipSeconds = minClipSeconds;
    }

    public int getMaxClipSeconds() {
        return maxClipSeconds;
    }

    public void setMaxClipSeconds(int maxClipSeconds) {
        this.maxClipSeconds = maxClipSeconds;
    }

    public String getCaptionPreset() {
        return captionPreset;
    }

    public void setCaptionPreset(String captionPreset) {
        this.captionPreset = captionPreset;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getTranscriptJson() {
        return transcriptJson;
    }

    public void setTranscriptJson(String transcriptJson) {
        this.transcriptJson = transcriptJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
