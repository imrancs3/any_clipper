package com.anyclipper.job;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** Database access for {@link Clip}. */
public interface ClipRepository extends JpaRepository<Clip, Long> {

    List<Clip> findByJobIdOrderByScoreDesc(Long jobId);

    @Transactional
    void deleteByJobId(Long jobId);
}
