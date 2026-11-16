-- AnimeQuiz: разбивка тайтлов по полноте данных и состоянию постера.
-- Запрос только читает данные и ничего не меняет.
SET search_path TO main;

SELECT
    completeness,
    count(*)                                            AS total,
    count(*) FILTER (WHERE poster_status = 'ready')     AS poster_ready,
    count(*) FILTER (WHERE poster_status = 'pending')   AS poster_pending,
    count(*) FILTER (WHERE poster_status = 'failed')    AS poster_failed,
    count(*) FILTER (WHERE poster_status = 'no_image')  AS poster_no_image,
    count(*) FILTER (WHERE poster_hash IS NULL)         AS no_hash,
    count(*) FILTER (WHERE NOT is_active)               AS inactive
FROM anime
GROUP BY completeness
ORDER BY CASE completeness
             WHEN 'stub'    THEN 1
             WHEN 'partial' THEN 2
             WHEN 'full'    THEN 3
             ELSE 4
         END;
