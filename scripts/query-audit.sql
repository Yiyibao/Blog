create extension if not exists pg_stat_statements;

-- Run against a restored production-like database, then archive the output in
-- the release checkpoint. These are the public high-frequency query shapes.
explain (analyze, buffers, format text)
select id, slug, title, published_date from posts
where status = 'PUBLISHED' order by published_date desc limit 20;

explain (analyze, buffers, format text)
select id, slug, name from dishes
where published = true order by featured desc, display_order, id limit 20;

select calls, mean_exec_time, rows, left(query, 180) as query
from pg_stat_statements
where dbid = (select oid from pg_database where datname = current_database())
order by total_exec_time desc limit 20;
