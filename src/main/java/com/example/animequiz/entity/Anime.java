package com.example.animequiz.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "anime")
@Getter
@Setter
public class Anime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shikimori_id", unique = true)
    private Long shikimoriId;

    @Column(name = "mal_id", unique = true)
    private Long malId;

    @Column(name = "yummy_id", unique = true)
    private Long yummyId;

    @Column(length = 512) private String name;
    @Column(length = 512) private String russian;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(length = 32)  private String kind;
    @Column(name = "airing_status", length = 32) private String airingStatus;
    @Column(name = "total_episodes") private Integer totalEpisodes;
    @Column(length = 1024) private String url;

    @Column(name = "poster_hash", length = 64)  private String posterHash;
    @Column(name = "poster_ext", length = 8)    private String posterExt;
    @Column(name = "poster_mime", length = 64)  private String posterMime;
    @Column(name = "poster_url", length = 1024) private String posterUrl;

    @Column(name = "poster_status", nullable = false, length = 16)
    private String posterStatus = "pending";

    @Column(nullable = false, length = 16)
    private String completeness = "stub";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "enrich_attempts", nullable = false)
    private int enrichAttempts = 0;

    @Column(name = "enrich_last_error", columnDefinition = "text")
    private String enrichLastError;

    @Column(name = "enrich_next_at")
    private Instant enrichNextAt;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "enriched_sources", columnDefinition = "text[]")
    private String[] enrichedSources;

    @Column(name = "enriched_at") private Instant enrichedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}