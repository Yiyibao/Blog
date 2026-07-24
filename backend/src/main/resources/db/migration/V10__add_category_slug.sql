alter table posts add column category_slug varchar(255) not null default '';

update posts set category_slug = encode(convert_to(category, 'UTF8'), 'hex');

alter table posts alter column category_slug drop default;
