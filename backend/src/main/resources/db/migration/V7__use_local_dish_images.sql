UPDATE dishes
SET image_url = CASE slug
    WHEN 'dongpo-pork' THEN '/food/dongpo-pork.jpg'
    WHEN 'handmade-jiaozi' THEN '/food/jiaozi.jpg'
    WHEN 'garlic-broccoli' THEN '/food/stir-fried-broccoli.jpg'
    ELSE image_url
END
WHERE slug IN ('dongpo-pork', 'handmade-jiaozi', 'garlic-broccoli');
