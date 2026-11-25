package com.anyclipper.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests the parts of link handling that are easy to get subtly wrong: link parsing and progress lines. */
class DownloadServiceTest {

    @Test
    void parsesProgressWithKnownTotalSize() {
        var progress = DownloadService.parseYtDlpProgress("CLIPPER_PROGRESS:52428800|104857600|NA|3145728.0|16");

        assertThat(progress).isPresent();
        assertThat(progress.get().percent()).isEqualTo(50);
        assertThat(progress.get().speedBytesPerSecond()).isEqualTo(3145728.0);
        assertThat(progress.get().etaSeconds()).isEqualTo(16);
    }

    @Test
    void fallsBackToEstimatedTotalSize() {
        var progress = DownloadService.parseYtDlpProgress("CLIPPER_PROGRESS:25|NA|100.0|NA|NA");

        assertThat(progress).isPresent();
        assertThat(progress.get().percent()).isEqualTo(25);
        assertThat(progress.get().speedBytesPerSecond()).isEqualTo(-1);
        assertThat(progress.get().etaSeconds()).isEqualTo(-1);
    }

    @Test
    void reportsUnknownPercentWhenNoSizeIsKnown() {
        var progress = DownloadService.parseYtDlpProgress("CLIPPER_PROGRESS:1000|NA|NA|500|NA");

        assertThat(progress).isPresent();
        assertThat(progress.get().percent()).isEqualTo(-1);
    }

    @Test
    void ignoresOtherOutputLines() {
        assertThat(DownloadService.parseYtDlpProgress("[youtube] abc: Downloading webpage")).isEmpty();
        assertThat(DownloadService.parseYtDlpProgress("CLIPPER_PROGRESS:broken")).isEmpty();
    }

    @Test
    void buildsReadableProgressMessage() {
        assertThat(DownloadService.progressMessage(42, 3.1 * 1024 * 1024, 130))
                .isEqualTo("Downloading 42% at 3.1 MiB/s, 2:10 left");
        assertThat(DownloadService.progressMessage(-1, -1, -1)).isEqualTo("Downloading");
    }

    @Test
    void recognisesGoogleDriveFileLinks() {
        String shareLink = "https://drive.google.com/file/d/1AbCdEfGhIjKlMnOpQrStUvWxYz012345/view?usp=sharing";
        String openLink = "https://drive.google.com/open?id=1AbCdEfGhIjKlMnOpQrStUvWxYz012345";

        assertThat(DownloadService.isGoogleDriveLink(shareLink)).isTrue();
        assertThat(DownloadService.googleDriveFileId(shareLink)).contains("1AbCdEfGhIjKlMnOpQrStUvWxYz012345");
        assertThat(DownloadService.googleDriveFileId(openLink)).contains("1AbCdEfGhIjKlMnOpQrStUvWxYz012345");
    }

    @Test
    void driveFolderLinksHaveNoFileId() {
        String folderLink = "https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOpQrStUvWxYz012345";

        assertThat(DownloadService.isGoogleDriveLink(folderLink)).isTrue();
        assertThat(DownloadService.googleDriveFileId(folderLink)).isEmpty();
    }

    @Test
    void youTubeLinksAreNotDriveLinks() {
        assertThat(DownloadService.isGoogleDriveLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).isFalse();
    }
}
