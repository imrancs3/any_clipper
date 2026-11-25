package com.anyclipper.config;

import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web setup: streamed uploads, and serving the built frontend from this same process.
 *
 * <p>Running the frontend from Spring Boot means normal use is a single java process (important on a
 * low-end PC). The Vite dev server is only needed while working on the frontend itself.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ClipperProperties properties;

    public WebConfig(ClipperProperties properties) {
        this.properties = properties;
    }

    /**
     * Upload settings. A size threshold of 0 makes Tomcat write every uploaded byte straight to a temp
     * file in data/upload-tmp instead of memory. The folder must be an absolute path (Tomcat would
     * otherwise put it in its own work folder) and on the same drive as data/jobs, so moving the
     * finished upload into the job folder is a cheap rename. Defining this bean replaces Spring
     * Boot's own multipart settings.
     */
    @Bean
    MultipartConfigElement multipartConfigElement() {
        Path uploadTempDir = properties.uploadTempDir().toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadTempDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the upload folder " + uploadTempDir, e);
        }
        long maxBytes = properties.maxUploadSize().toBytes();
        return new MultipartConfigElement(uploadTempDir.toString(), maxBytes, maxBytes, 0);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String frontendLocation = properties.frontendDir().toAbsolutePath().normalize().toUri().toString();
        if (!frontendLocation.endsWith("/")) {
            frontendLocation += "/";
        }
        registry.addResourceHandler("/**").addResourceLocations(frontendLocation);
    }

    // React Router owns these page URLs; the browser must get index.html for them, also after a reload.
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/jobs/{id}").setViewName("forward:/index.html");
    }
}
