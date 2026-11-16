-- AnimeQuiz: топ-50 тайтлов по числу списков пользователей.
-- Помогает понять, какие тайтлы важнее всего обогащать в первую очередь.
-- Запрос только читает данные и ничего не меняет.
SET search_path TO main;

SELECT
    a.id,
    a.title,
    a.russian,
    a.completeness,
    a.poster_status,
    a.is_active,
    count(ua.id) AS in_user_lists,
    a.enrich_attempts
FROM anime a
LEFT JOIN user_anime ua ON ua.anime_id = a.id
GROUP BY a.id
ORDER BY in_user_lists DESC, a.id
LIMIT 50;
