package com.example.animequiz.repository;

import com.example.animequiz.entity.Anime;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AnimeRepository extends JpaRepository<Anime, Long> {

    Optional<Anime> findByShikimoriId(Long shikimoriId);

    Optional<Anime> findByMalId(Long malId);

    Optional<Anime> findByYummyId(Long yummyId);

    @Query("select a from Anime a where a.shikimoriId in :ids")
    List<Anime> findAllByShikimoriIdIn(@Param("ids") List<Long> ids);

    @Query("select a from Anime a where a.malId in :ids")
    List<Anime> findAllByMalIdIn(@Param("ids") List<Long> ids);

    @Query(value = """
            SELECT a.* FROM anime a
            LEFT JOIN (
                SELECT anime_id, COUNT(*) AS cnt FROM user_anime GROUP BY anime_id
            ) ua ON ua.anime_id = a.id
            WHERE a.is_active = true
              AND (
                  a.completeness = 'stub'
                  OR (a.poster_status IN ('pending','failed') AND a.poster_hash IS NULL)
              )
              AND (a.enrich_next_at IS NULL OR a.enrich_next_at <= :now)
            ORDER BY
                CASE WHEN a.completeness = 'stub' THEN 1 ELSE 0 END DESC,
                COALESCE(ua.cnt, 0) DESC,
                COALESCE(a.enrich_next_at, a.created_at)
            """,
            nativeQuery = true)
    List<Anime> findEnrichQueue(@Param("now") Instant now, Pageable pageable);
}