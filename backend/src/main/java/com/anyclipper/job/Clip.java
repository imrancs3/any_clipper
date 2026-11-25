package com.anyclipper.job;

import com.anyclipper.common.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.ArrayList;
import java.util.List;

/**
 * One short clip found in a job's source video, with its post text and render state.
 *
 * <p>Created by clip selection (M3), cut and rendered in M4 and M5, edited in M6. The table is created
 * in M1 so the schema is complete from the start. The clip's files live in the job folder:
 * segments/{id}.mp4 and clips/{id}.mp4.
 */
@Entity
public class Clip {

    /** Render state of a single clip, so one clip can be re-rendered without touching the others. */
    public enum Status {
        PENDING,
        CUTTING,
        RENDERING,
        READY,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The job this clip belongs to. A plain id, so loading a clip never loads its job. */
    @Column(nullable = false)
    private Long jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    /** Exact clip start in the source video, in seconds. */
    private double startSeconds;

    /** Exact clip end in the source video, in seconds. */
    private double endSeconds;

    /** How strong the moment is, 0 to 100, as rated by the clip selection model. */
    private int score;

    @Column(length = 200)
    private String title;

    /** Two alternative titles in different styles (stored as JSON text). */
    @Column(length = 2000)
    @Convert(converter = StringListConverter.class)
    private List<String> altTitles = new ArrayList<>();

    /** The opening line that grabs attention. */
    @Lob
    private String hook;

    /** Post caption, at most 200 characters after validation. */
    @Column(length = 500)
    private String description;

    /** Normalised hashtags such as "#ai" (stored as JSON text). */
    @Column(length = 2000)
    @Convert(converter = StringListConverter.class)
    private List<String> hashtags = new ArrayList<>();

    /** Plain topic keywords for SEO and YouTube tags (stored as JSON text). */
    @Column(length = 2000)
    @Convert(converter = StringListConverter.class)
    private List<String> keywords = new ArrayList<>();

    /** Why the model picked this moment. */
    @Lob
    private String reason;

    /** Where the stream-copied segment really starts (the keyframe before the pre-roll). trimStart = startSeconds - segmentStartSeconds. */
    private Double segmentStartSeconds;

    @Column(length = 30)
    private String captionPreset;

    /** Caption words edited by the user as JSON; null means use the transcript. */
    @Lob
    private String captionWordsJson;

    @Lob
    private String errorMessage;

    private Instant createdAt;
    private Instant updatedAt;

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

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public double getStartSeconds() {
        return startSeconds;
    }

    public void setStartSeconds(double startSeconds) {
        this.startSeconds = startSeconds;
    }

    public double getEndSeconds() {
        return endSeconds;
    }

    public void setEndSeconds(double endSeconds) {
        this.endSeconds = endSeconds;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<String> getAltTitles() {
        return altTitles;
    }

    public void setAltTitles(List<String> altTitles) {
        this.altTitles = altTitles;
    }

    public String getHook() {
        return hook;
    }

    public void setHook(String hook) {
        this.hook = hook;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getHashtags() {
        return hashtags;
    }

    public void setHashtags(List<String> hashtags) {
        this.hashtags = hashtags;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Double getSegmentStartSeconds() {
        return segmentStartSeconds;
    }

    public void setSegmentStartSeconds(Double segmentStartSeconds) {
        this.segmentStartSeconds = segmentStartSeconds;
    }

    public String getCaptionPreset() {
        return captionPreset;
    }

    public void setCaptionPreset(String captionPreset) {
        this.captionPreset = captionPreset;
    }

    public String getCaptionWordsJson() {
        return captionWordsJson;
    }

    public void setCaptionWordsJson(String captionWordsJson) {
        this.captionWordsJson = captionWordsJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
