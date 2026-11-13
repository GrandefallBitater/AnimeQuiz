package com.example.animequiz.repository;

import com.example.animequiz.entity.ImportJob;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {

    Optional<ImportJob> findFirstByTypeAndStateOrderByIdDesc(String type, String state);

    List<ImportJob> findTop10ByTypeOrderByIdDesc(String type);

    @Transactional
    @Modifying
    @Query("update ImportJob j set j.state='failed', j.finishedAt=:now, j.error=:reason where j.state='running'")
    int failAllRunningJobs(@Param("now") Instant now, @Param("reason") String reason);
}
