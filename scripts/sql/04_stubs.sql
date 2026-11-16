-- AnimeQuiz: список заглушек (completeness = 'stub').
-- Сначала показываются тайтлы, которые чаще всего встречаются в списках.
-- Уберите LIMIT, если нужно увидеть все заглушки.
-- Запрос только читает данные и ничего не меняет.
SET search_path TO main;

SELECT
    a.id,
    a.shikimori_id,
    a.mal_id,
    a.yummy_id,
    a.title,
    a.russian,
    a.poster_status,
    a.enrich_attempts,
    a.enrich_last_error,
    a.enrich_next_at,
    a.created_at,
    (SELECT count(*)
     FROM user_anime ua
     WHERE ua.anime_id = a.id) AS in_user_lists
FROM anime a
WHERE a.completeness = 'stub'
ORDER BY in_user_lists DESC, a.id
LIMIT 200;
