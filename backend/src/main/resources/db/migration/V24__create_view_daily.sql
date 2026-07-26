-- 4D：全站日浏览趋势——详情读去重窗口命中时 UPSERT 当日 +1，保留最近 180 天
create table view_daily (
    day date primary key,
    views bigint not null default 0
);
