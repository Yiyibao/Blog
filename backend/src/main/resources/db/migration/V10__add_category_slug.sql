ALTER TABLE posts ADD COLUMN category_slug VARCHAR(255) NOT NULL DEFAULT '';

UPDATE posts SET category_slug = LOWER(TRIM(category));

ALTER TABLE posts ALTER COLUMN category_slug DROP DEFAULT;
