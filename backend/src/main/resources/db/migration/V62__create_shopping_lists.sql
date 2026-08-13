create table shopping_lists (
    id uuid primary key,
    owner_id bigint not null,
    week_start date not null,
    note varchar(500) not null default '',
    version bigint not null default 0,
    last_mutation_key varchar(160),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint uk_shopping_lists_owner_week unique (owner_id, week_start)
);

create index idx_shopping_lists_owner_updated on shopping_lists (owner_id, updated_at desc);

create table shopping_list_items (
    id uuid primary key,
    list_id uuid not null references shopping_lists(id) on delete cascade,
    normalized_name varchar(160) not null,
    display_name varchar(160) not null,
    quantity numeric(12, 3),
    unit varchar(32) not null default '',
    original_quantity varchar(240) not null default '',
    source_recipe varchar(500) not null default '',
    category varchar(60) not null default '未分类',
    checked boolean not null default false,
    manual boolean not null default false,
    note varchar(240) not null default '',
    sort_order integer not null default 0,
    created_at timestamp with time zone not null default current_timestamp
);

create index idx_shopping_list_items_list_sort on shopping_list_items (list_id, sort_order, id);
create index idx_shopping_list_items_list_name on shopping_list_items (list_id, normalized_name, unit);
