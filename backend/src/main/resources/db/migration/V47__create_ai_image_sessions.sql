create table if not exists ai_image_sessions (
    id bigserial primary key,
    owner varchar(64) not null,
    title varchar(100),
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now()
);

create index if not exists idx_ai_image_sessions_owner_updated on ai_image_sessions(owner, updated_at desc);

alter table ai_generated_images add column if not exists session_id bigint;

-- 存量数据：每一条旧的 generation 视为一次独立会话（归入默认账号）
do $$
declare
    rec record;
    sid bigint;
begin
    for rec in
        select generation_id,
               left(regexp_replace(min(prompt), '\s+', ' ', 'g'), 10) as title,
               min(created_at) as created,
               max(created_at) as updated
        from ai_generated_images
        where session_id is null
        group by generation_id
        order by min(created_at)
    loop
        insert into ai_image_sessions (owner, title, created_at, updated_at)
        values ('admin', rec.title, rec.created, rec.updated)
        returning id into sid;
        update ai_generated_images set session_id = sid where generation_id = rec.generation_id;
    end loop;
end $$;

alter table ai_generated_images alter column session_id set not null;

create index if not exists idx_ai_generated_images_session on ai_generated_images(session_id, created_at);
