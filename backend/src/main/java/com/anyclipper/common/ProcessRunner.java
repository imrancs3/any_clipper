package com.anyclipper.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs command line tools (ffmpeg, ffprobe, yt-dlp) and hands their output to the caller line by line.
 *
 * <p>This is the only place in Clipper that uses {@link ProcessBuilder}. Arguments are always passed
 * as a list, never through a shell, so file names and URLs cannot be interpreted as shell syntax.
 */
@Component
public class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);

    /** How many of the last output lines are kept for error messages. */
    private static final int KEPT_LINES = 15;

    /**
     * Result of a finished command.
     *
     * @param exitCode the process exit code (0 means success for all tools Clipper uses)
     * @param lastLines the last few output lines, useful in error messages
     */
    public record Result(int exitCode, List<String> lastLines) {

        public boolean succeeded() {
            return exitCode == 0;
        }

        public String lastLinesText() {
            return String.join("\n", lastLines);
        }
    }

    /**
     * Runs a command and waits for it to finish.
     *
     * @param command the tool and its arguments
     * @param timeout the process is killed if it runs longer than this
     * @param onLine called for every output line (stdout and stderr merged), on a background thread
     * @return the exit code and the last output lines
     * @throws ClipperException if the tool cannot be started, times out, or the wait is interrupted
     */
    public Result run(List<String> command, Duration timeout, Consumer<String> onLine) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new ClipperException("Could not start '" + command.getFirst()
                    + "'. Is it installed and on PATH?", e);
        }

        // Read the output on its own thread so a chatty tool never blocks on a full pipe
        // while we wait for it to exit.
        Deque<String> lastLines = new ArrayDeque<>();
        Thread reader = Thread.ofVirtual().start(() -> readLines(process, lastLines, onLine));

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ClipperException("'" + command.getFirst() + "' did not finish within "
                        + timeout.toMinutes() + " minutes and was stopped");
            }
            reader.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ClipperException("Interrupted while waiting for '" + command.getFirst() + "'", e);
        }

        synchronized (lastLines) {
            return new Result(process.exitValue(), new ArrayList<>(lastLines));
        }
    }

    /** Runs a command and ignores its output, for short calls such as "ffmpeg -version". */
    public Result run(List<String> command, Duration timeout) {
        return run(command, timeout, line -> { });
    }

    private void readLines(Process process, Deque<String> lastLines, Consumer<String> onLine) {
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
                synchronized (lastLines) {
                    lastLines.addLast(line);
                    if (lastLines.size() > KEPT_LINES) {
                        lastLines.removeFirst();
                    }
                }
                onLine.accept(line);
            }
        } catch (IOException e) {
            // The stream closes when the process is killed; nothing useful is lost.
            log.debug("Output stream of process closed: {}", e.getMessage());
        }
    }
}
