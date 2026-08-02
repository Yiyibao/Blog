-- Replace the twenty catalog SVG references with the generated, real-food
-- photography assets. The historical SVG files stay in public/food/generated
-- as a rollback fallback, but no published dish points at them after V40.

update dishes as d
set image_url = v.image_url,
    image_alt = v.image_alt,
    image_credit = 'Yubai Studio - AI-generated food photography'
from (values
    ('authentic-mapo-tofu', '/food/real/authentic-mapo-tofu.webp', '红油、肉末和青蒜点缀的麻婆豆腐'),
    ('kung-pao-chicken', '/food/real/kung-pao-chicken.webp', '鸡丁、花生和干辣椒组成的宫保鸡丁'),
    ('dongpo-pork', '/food/real/dongpo-pork.webp', '酱汁浓郁、葱丝点缀的东坡肉'),
    ('lotus-root-pork-rib-soup', '/food/real/lotus-root-pork-rib-soup.webp', '莲藕和排骨熬成的清润汤品'),
    ('handmade-jiaozi', '/food/real/handmade-jiaozi.webp', '竹盘中排列的手工水饺'),
    ('scallion-oil-noodles', '/food/real/scallion-oil-noodles.webp', '葱油和酱汁拌匀的细面'),
    ('garlic-broccoli', '/food/real/garlic-broccoli.webp', '蒜片点缀的清炒西兰花'),
    ('tomato-scrambled-eggs', '/food/real/tomato-scrambled-eggs.webp', '番茄和嫩炒鸡蛋组成的家常菜'),
    ('sweet-sour-pork', '/food/real/sweet-sour-pork.webp', '菠萝和彩椒搭配的酸甜咕咾肉'),
    ('steamed-sea-bass', '/food/real/steamed-sea-bass.webp', '姜葱铺在鱼身上的清蒸鲈鱼'),
    ('soy-sauce-chicken', '/food/real/soy-sauce-chicken.webp', '酱油收汁的带皮鸡腿与姜葱'),
    ('winter-melon-soup', '/food/real/winter-melon-soup.webp', '冬瓜、虾皮和葱花组成的清汤'),
    ('tea-fragrant-ribs', '/food/real/tea-fragrant-ribs.webp', '红茶和酱汁收味的茶香排骨'),
    ('pan-fried-mushroom', '/food/real/pan-fried-mushroom.webp', '香煎蘑菇和芦笋的时蔬拼盘'),
    ('pumpkin-millet-porridge', '/food/real/pumpkin-millet-porridge.webp', '南瓜小米熬成的金黄粥品'),
    ('cucumber-shrimp', '/food/real/cucumber-shrimp.webp', '黄瓜和鲜虾仁快速炒制的家常菜'),
    ('scallion-pancake', '/food/real/scallion-pancake.webp', '葱花和芝麻点缀的发面饼'),
    ('three-cup-chicken', '/food/real/three-cup-chicken.webp', '米酒、酱油、麻油和九层塔烹制的三杯鸡'),
    ('miso-salmon', '/food/real/miso-salmon.webp', '味噌蜂蜜烤制的三文鱼排'),
    ('red-bean-rice-cake', '/food/real/red-bean-rice-cake.webp', '糯米和红豆分层蒸制的米糕')
) as v(slug, image_url, image_alt)
where d.slug = v.slug;
