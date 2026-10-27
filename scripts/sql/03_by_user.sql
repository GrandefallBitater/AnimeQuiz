-- AnimeQuiz: сколько тайтлов в списке каждого пользователя и сколько из них заглушек.
-- Запрос только читает данные и ничего не меняет.
SET search_path TO main;

SELECT
    ua.user_id,
    count(*)                                                       AS titles_in_list,
    count(*) FILTER (WHERE a.completeness = 'stub')                AS stubs,
    count(*) FILTER (WHERE a.completeness = 'partial')             AS partial,
    count(*) FILTER (WHERE a.completeness = 'full')                AS full,
    count(*) FILTER (WHERE a.completeness = 'stub'
                       AND a.poster_hash IS NULL)                  AS stubs_without_poster,
    count(*) FILTER (WHERE a.poster_hash IS NULL)                  AS without_poster,
    count(*) FILTER (WHERE a.poster_status = 'failed')             AS poster_failed
FROM user_anime ua
JOIN anime a ON a.id = ua.anime_id
GROUP BY ua.user_id
ORDER BY stubs DESC, titles_in_list DESC, ua.user_id;
