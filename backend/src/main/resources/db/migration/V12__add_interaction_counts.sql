alter table dishes add column if not exists favorite_count integer not null default 0;

alter table posts add column if not exists like_count integer not null default 0;
alter table posts add column if not exists views_count integer not null default 0;

create index if not exists dishes_favorite_count_idx on dishes (favorite_count desc);
create index if not exists posts_like_count_idx on posts (like_count desc);
create index if not exists posts_views_count_idx on posts (views_count desc);
