package com.example.animequiz.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "import_job")
@Getter
@Setter
public class ImportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(length = 32)
    private String source;

    @Column(nullable = false, length = 16)
    private String state = "running";

    @Column(length = 32)
    private String stage;

    @Column(name = "processed_count", nullable = false)
    private int processedCount = 0;

    @Column(name = "total_estimate")
    private Integer totalEstimate;

    @Column(columnDefinition = "text")
    private String message;

    @Column(columnDefinition = "text")
    private String error;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> checkpoint;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;
}
