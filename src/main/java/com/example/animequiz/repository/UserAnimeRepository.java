package com.example.animequiz.repository;

import com.example.animequiz.entity.UserAnime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserAnimeRepository extends JpaRepository<UserAnime, Long> {

    List<UserAnime> findByUserId(String userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserAnime ua where ua.userId = :userId")
    void deleteAllByUserId(@Param("userId") String userId);

    @Modifying
    @Query(value = """
        DELETE FROM user_anime ua
        WHERE ua.anime_id = :dropId
          AND EXISTS (
              SELECT 1 FROM user_anime ua2
              WHERE ua2.user_id = ua.user_id AND ua2.anime_id = :keepId
          )
        """, nativeQuery = true)
    void deleteConflictingUserAnime(@Param("dropId") Long dropId, @Param("keepId") Long keepId);

    @Modifying
    @Query(value = """
        UPDATE user_anime SET anime_id = :keepId, updated_at = now()
        WHERE anime_id = :dropId
        """, nativeQuery = true)
    void moveUserAnimeToKeep(@Param("dropId") Long dropId, @Param("keepId") Long keepId);

    default void transferUserAnime(Long dropId, Long keepId) {
        deleteConflictingUserAnime(dropId, keepId);
        moveUserAnimeToKeep(dropId, keepId);
    }
}