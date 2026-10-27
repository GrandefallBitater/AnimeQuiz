-- AnimeQuiz: общая сводка по каталогу аниме.
-- Запрос только читает данные и ничего не меняет.
-- Если схема называется не main, поправьте строку SET search_path.
SET search_path TO main;

WITH a AS (
    SELECT * FROM anime
),
ua AS (
    SELECT anime_id, count(*) AS cnt
    FROM user_anime
    GROUP BY anime_id
)
SELECT metric, value, pct
FROM (
    SELECT 1 AS ord, 'Всего тайтлов в каталоге' AS metric,
           count(*)::bigint AS value, NULL::numeric AS pct
    FROM a

    UNION ALL
    SELECT 2, 'Активных (is_active = true)',
           count(*) FILTER (WHERE is_active),
           round(100.0 * count(*) FILTER (WHERE is_active) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 3, 'Неактивных (is_active = false)',
           count(*) FILTER (WHERE NOT is_active),
           round(100.0 * count(*) FILTER (WHERE NOT is_active) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 4, 'Заглушки (completeness = stub)',
           count(*) FILTER (WHERE completeness = 'stub'),
           round(100.0 * count(*) FILTER (WHERE completeness = 'stub') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 5, 'Частичные (completeness = partial)',
           count(*) FILTER (WHERE completeness = 'partial'),
           round(100.0 * count(*) FILTER (WHERE completeness = 'partial') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 6, 'Полные (completeness = full)',
           count(*) FILTER (WHERE completeness = 'full'),
           round(100.0 * count(*) FILTER (WHERE completeness = 'full') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 7, 'Прочее/неизвестное значение completeness',
           count(*) FILTER (WHERE completeness NOT IN ('stub', 'partial', 'full')),
           round(100.0 * count(*) FILTER (WHERE completeness NOT IN ('stub', 'partial', 'full')) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 8, 'Заглушки с title = ''Unknown #...''',
           count(*) FILTER (WHERE completeness = 'stub' AND title LIKE 'Unknown #%'),
           round(100.0 * count(*) FILTER (WHERE completeness = 'stub' AND title LIKE 'Unknown #%') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 9, 'Заглушки без постера (poster_hash IS NULL)',
           count(*) FILTER (WHERE completeness = 'stub' AND poster_hash IS NULL),
           round(100.0 * count(*) FILTER (WHERE completeness = 'stub' AND poster_hash IS NULL) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 10, 'Постер: ready',
           count(*) FILTER (WHERE poster_status = 'ready'),
           round(100.0 * count(*) FILTER (WHERE poster_status = 'ready') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 11, 'Постер: pending',
           count(*) FILTER (WHERE poster_status = 'pending'),
           round(100.0 * count(*) FILTER (WHERE poster_status = 'pending') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 12, 'Постер: failed',
           count(*) FILTER (WHERE poster_status = 'failed'),
           round(100.0 * count(*) FILTER (WHERE poster_status = 'failed') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 13, 'Постер: no_image',
           count(*) FILTER (WHERE poster_status = 'no_image'),
           round(100.0 * count(*) FILTER (WHERE poster_status = 'no_image') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 14, 'Без poster_hash (постера ещё нет)',
           count(*) FILTER (WHERE poster_hash IS NULL),
           round(100.0 * count(*) FILTER (WHERE poster_hash IS NULL) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 15, 'Без poster_url',
           count(*) FILTER (WHERE poster_url IS NULL OR poster_url = ''),
           round(100.0 * count(*) FILTER (WHERE poster_url IS NULL OR poster_url = '') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 16, 'poster_url — заглушка (missing_preview)',
           count(*) FILTER (WHERE poster_url LIKE '%missing_preview%'),
           round(100.0 * count(*) FILTER (WHERE poster_url LIKE '%missing_preview%') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 17, 'Без russian',
           count(*) FILTER (WHERE russian IS NULL OR russian = ''),
           round(100.0 * count(*) FILTER (WHERE russian IS NULL OR russian = '') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 18, 'Без name',
           count(*) FILTER (WHERE name IS NULL OR name = ''),
           round(100.0 * count(*) FILTER (WHERE name IS NULL OR name = '') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 19, 'Без kind',
           count(*) FILTER (WHERE kind IS NULL OR kind = ''),
           round(100.0 * count(*) FILTER (WHERE kind IS NULL OR kind = '') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 20, 'Без total_episodes',
           count(*) FILTER (WHERE total_episodes IS NULL),
           round(100.0 * count(*) FILTER (WHERE total_episodes IS NULL) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 21, 'Без airing_status',
           count(*) FILTER (WHERE airing_status IS NULL OR airing_status = ''),
           round(100.0 * count(*) FILTER (WHERE airing_status IS NULL OR airing_status = '') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 22, 'Без url',
           count(*) FILTER (WHERE url IS NULL OR url = ''),
           round(100.0 * count(*) FILTER (WHERE url IS NULL OR url = '') / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 23, 'Ещё не обогащались (enriched_at IS NULL)',
           count(*) FILTER (WHERE enriched_at IS NULL),
           round(100.0 * count(*) FILTER (WHERE enriched_at IS NULL) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 24, 'Попыток обогащения = 0',
           count(*) FILTER (WHERE enrich_attempts = 0),
           round(100.0 * count(*) FILTER (WHERE enrich_attempts = 0) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 25, 'Попыток обогащения > 0',
           count(*) FILTER (WHERE enrich_attempts > 0),
           round(100.0 * count(*) FILTER (WHERE enrich_attempts > 0) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 26, 'С ошибкой (enrich_last_error IS NOT NULL)',
           count(*) FILTER (WHERE enrich_last_error IS NOT NULL),
           round(100.0 * count(*) FILTER (WHERE enrich_last_error IS NOT NULL) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 27, 'Ждут повтора (enrich_next_at > now())',
           count(*) FILTER (WHERE enrich_next_at > now()),
           round(100.0 * count(*) FILTER (WHERE enrich_next_at > now()) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 28, 'Просрочены к обработке (enrich_next_at <= now())',
           count(*) FILTER (WHERE enrich_next_at <= now()),
           round(100.0 * count(*) FILTER (WHERE enrich_next_at <= now()) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 29, '5+ неудачных попыток (enrich_attempts >= 5)',
           count(*) FILTER (WHERE enrich_attempts >= 5),
           round(100.0 * count(*) FILTER (WHERE enrich_attempts >= 5) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 30, 'Есть shikimori_id',
           count(*) FILTER (WHERE shikimori_id IS NOT NULL),
           round(100.0 * count(*) FILTER (WHERE shikimori_id IS NOT NULL) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 31, 'Есть mal_id',
           count(*) FILTER (WHERE mal_id IS NOT NULL),
           round(100.0 * count(*) FILTER (WHERE mal_id IS NOT NULL) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 32, 'Есть yummy_id',
           count(*) FILTER (WHERE yummy_id IS NOT NULL),
           round(100.0 * count(*) FILTER (WHERE yummy_id IS NOT NULL) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 33, 'Только один источник ID',
           count(*) FILTER (
               WHERE (shikimori_id IS NOT NULL)::int
                   + (mal_id IS NOT NULL)::int
                   + (yummy_id IS NOT NULL)::int = 1
           ),
           round(100.0 * count(*) FILTER (
               WHERE (shikimori_id IS NOT NULL)::int
                   + (mal_id IS NOT NULL)::int
                   + (yummy_id IS NOT NULL)::int = 1
           ) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 34, 'Ровно два источника ID',
           count(*) FILTER (
               WHERE (shikimori_id IS NOT NULL)::int
                   + (mal_id IS NOT NULL)::int
                   + (yummy_id IS NOT NULL)::int = 2
           ),
           round(100.0 * count(*) FILTER (
               WHERE (shikimori_id IS NOT NULL)::int
                   + (mal_id IS NOT NULL)::int
                   + (yummy_id IS NOT NULL)::int = 2
           ) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 35, 'Все три источника ID',
           count(*) FILTER (
               WHERE (shikimori_id IS NOT NULL)::int
                   + (mal_id IS NOT NULL)::int
                   + (yummy_id IS NOT NULL)::int = 3
           ),
           round(100.0 * count(*) FILTER (
               WHERE (shikimori_id IS NOT NULL)::int
                   + (mal_id IS NOT NULL)::int
                   + (yummy_id IS NOT NULL)::int = 3
           ) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 36, 'Есть хотя бы в одном списке пользователей',
           count(*) FILTER (WHERE a.id IN (SELECT anime_id FROM ua)),
           round(100.0 * count(*) FILTER (WHERE a.id IN (SELECT anime_id FROM ua)) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 37, 'Сироты (нет ни в одном списке)',
           count(*) FILTER (WHERE a.id NOT IN (SELECT anime_id FROM ua)),
           round(100.0 * count(*) FILTER (WHERE a.id NOT IN (SELECT anime_id FROM ua)) / NULLIF(count(*), 0), 1)
    FROM a

    UNION ALL
    SELECT 38, 'Всего строк в user_anime',
           (SELECT count(*)::bigint FROM user_anime), NULL::numeric

    UNION ALL
    SELECT 39, 'Уникальных пользователей в user_anime',
           (SELECT count(DISTINCT user_id)::bigint FROM user_anime), NULL::numeric
) s
ORDER BY ord;
