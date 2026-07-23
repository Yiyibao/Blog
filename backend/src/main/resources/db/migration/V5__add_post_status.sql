alter table posts
    add column status varchar(20) not null default 'PUBLISHED';

alter table posts
    add constraint posts_status_check check (status in ('DRAFT', 'PUBLISHED'));

create index posts_status_date_idx on posts (status, published_date desc);
