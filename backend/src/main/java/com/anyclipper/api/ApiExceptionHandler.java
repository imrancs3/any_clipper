package com.anyclipper.api;

import com.anyclipper.common.ClipperException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Turns exceptions into short JSON answers: {@code {"error": "..."}}, which the frontend shows as-is.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ClipperException.class)
    public ResponseEntity<Map<String, String>> handleClipperException(ClipperException e) {
        if (e.getStatus().is5xxServerError()) {
            log.error("Request failed: {}", e.getMessage(), e);
        }
        return error(e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleTooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "The file is larger than the upload limit (clipper.max-upload-size)");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestPartException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "The request is incomplete or not valid JSON");
    }

    /**
     * Last resort for anything unexpected: logs the full stack trace to the console and still answers
     * with a readable message, so the browser never shows a bare "500". Spring's own web exceptions
     * (404, 405, ...) keep their status.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            String detail = errorResponse.getBody().getDetail();
            return error(status, detail != null ? detail : status.getReasonPhrase());
        }
        log.error("Unexpected error while handling a request", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error: " + e
                + ". The Clipper console window shows the full details.");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
