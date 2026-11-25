package com.anyclipper.job;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Database access for {@link Job}. */
public interface JobRepository extends JpaRepository<Job, Long> {

    List<Job> findAllByOrderByCreatedAtDesc();

    List<Job> findByStatusNotIn(Collection<JobStatus> statuses);
}
