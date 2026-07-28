insert into post_categories (name, slug)
select distinct on (lower(btrim(category))) btrim(category), category_slug
from posts
where btrim(category) <> ''
order by lower(btrim(category)), id
on conflict do nothing;

insert into dish_categories (name, slug)
select distinct on (lower(btrim(category))) btrim(category), lower(btrim(category))
from dishes
where btrim(category) <> ''
order by lower(btrim(category)), id
on conflict do nothing;
