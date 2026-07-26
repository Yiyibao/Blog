-- 3C（v5 阶段 3）：P1-8 真实浏览量模式推广到笔记与菜谱。
-- 台账注：计划 3.3 曾把 V22 预留给 3A-5 的旧 HTML 列去留评审——该评审尚未定案且未必产生迁移，
-- 按「实际最高号 +1」规则本迁移使用 V22，评审若产生迁移顺延取号。

alter table learning_notes add column views_count integer not null default 0;
alter table dishes add column views_count integer not null default 0;
