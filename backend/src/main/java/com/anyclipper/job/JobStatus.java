package com.anyclipper.job;

/**
 * The stages a job moves through, in order. Stored in the database and pushed to the UI over SSE.
 *
 * <p>M1 uses QUEUED, DOWNLOADING, DONE and FAILED; the stages in between are added by M2 to M5.
 */
public enum JobStatus {
    QUEUED,
    DOWNLOADING,
    EXTRACTING_AUDIO,
    TRANSCRIBING,
    SELECTING_CLIPS,
    CUTTING,
    RENDERING,
    DONE,
    FAILED;

    /** True when the job will not change any more by itself. */
    public boolean isFinished() {
        return this == DONE || this == FAILED;
    }
}
