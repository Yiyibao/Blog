-- NF-12：菜品份量基准——替换此前硬编码 2 人份
alter table dishes add column base_servings integer not null default 2;
comment on column dishes.base_servings is '菜品份量基准（人份），食材按此比例缩放显示';
