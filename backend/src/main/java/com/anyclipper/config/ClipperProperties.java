package com.anyclipper.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * All Clipper settings, bound from the {@code clipper:} block of application.yml.
 *
 * <p>This is the single settings record of the app. Secrets (such as the access token) are read from
 * environment variables inside application.yml and are never logged. Later milestones add their own
 * fields here instead of creating new settings classes.
 *
 * @param dataDir root folder for the database, job folders and temporary uploads
 * @param accessToken value every /api request must carry (from APP_ACCESS_TOKEN)
 * @param tools names or full paths of the command line tools Clipper runs
 * @param download limits for URL downloads (YouTube, Google Drive and other links)
 * @param maxUploadSize largest file the upload form accepts
 * @param frontendDir folder with the built frontend (npm run build), served by this same process
 */
@ConfigurationProperties(prefix = "clipper")
public record ClipperProperties(
        @DefaultValue("./data") Path dataDir,
        String accessToken,
        @DefaultValue Tools tools,
        @DefaultValue Download download,
        @DefaultValue("20GB") DataSize maxUploadSize,
        @DefaultValue("../frontend/dist") Path frontendDir) {

    /**
     * Command line tools. Plain names are looked up on PATH; a full path also works.
     *
     * @param ytDlpEnabled false turns off yt-dlp links (Google Drive links and uploads still work)
     */
    public record Tools(
            @DefaultValue("ffmpeg") String ffmpeg,
            @DefaultValue("ffprobe") String ffprobe,
            @DefaultValue("yt-dlp") String ytDlp,
            @DefaultValue("true") boolean ytDlpEnabled) {
    }

    /**
     * URL download settings.
     *
     * @param maxHeight highest video resolution to download, in pixels (1080 = Full HD)
     * @param timeout a download that takes longer than this is stopped and the job fails
     */
    public record Download(
            @DefaultValue("1080") int maxHeight,
            @DefaultValue("2h") Duration timeout) {
    }

    /** Folder that holds one sub-folder per job: data/jobs/{jobId}/. */
    public Path jobsDir() {
        return dataDir.resolve("jobs");
    }

    /** One job's folder: data/jobs/{jobId}/ (source video, audio, segments, clips, transcript). */
    public Path jobDir(long jobId) {
        return jobsDir().resolve(String.valueOf(jobId));
    }

    /** Folder where Tomcat writes uploads while they arrive, before they move into the job folder. */
    public Path uploadTempDir() {
        return dataDir.resolve("upload-tmp");
    }
}
