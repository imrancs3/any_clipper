package com.anyclipper.common;

import org.springframework.http.HttpStatus;

/**
 * The one exception type of Clipper, used everywhere from request checks to pipeline stages.
 *
 * <p>The message is written for the user: the API returns it as-is, and a failed pipeline stage
 * stores it as the job's error message. The HTTP status only matters when it reaches the API.
 */
public class ClipperException extends RuntimeException {

    private final HttpStatus status;

    /** A problem with the request itself, such as a bad setting or an unsupported link. */
    public ClipperException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /** A problem while doing the work, such as a failed download. */
    public ClipperException(String message) {
        this(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    /** Same as above, keeping the original exception for the log. */
    public ClipperException(String message, Throwable cause) {
        super(message, cause);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
