package com.anyclipper;

import com.anyclipper.config.ClipperProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of Clipper, a personal tool that turns long videos into short vertical clips.
 *
 * <p>{@code @EnableAsync} lets each pipeline job run on its own (virtual) thread, and
 * {@code @EnableScheduling} drives the SSE keep-alive heartbeat.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(ClipperProperties.class)
public class ClipperApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClipperApplication.class, args);
    }
}
