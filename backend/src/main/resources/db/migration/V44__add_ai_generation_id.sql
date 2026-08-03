alter table ai_generated_images add column if not exists generation_id uuid;

-- 存量数据：每一张旧图视为独立的一次生成
update ai_generated_images set generation_id = public_id where generation_id is null;

alter table ai_generated_images alter column generation_id set not null;

create index if not exists idx_ai_generated_images_generation_id on ai_generated_images(generation_id);
