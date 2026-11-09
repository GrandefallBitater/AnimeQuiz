package com.example.animequiz.repository;

import com.example.animequiz.entity.ImportJob;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {

    Optional<ImportJob> findFirstByTypeAndStateOrderByIdDesc(String type, String state);

    List<ImportJob> findTop10ByTypeOrderByIdDesc(String type);
}
