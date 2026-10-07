package com.example.animequiz.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "user_anime",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "anime_id"}))
@Getter
@Setter
public class UserAnime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "anime_id", nullable = false)
    private Long animeId;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "source_rate_id", length = 64)
    private String sourceRateId;

    @Column(length = 16) private String status;
    private Integer score;

    @Column(name = "episodes_watched") private Integer episodesWatched;
    private Integer rewatches;

    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}