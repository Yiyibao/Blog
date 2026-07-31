-- Extend the public catalog to twenty published posts and twenty practical recipes.
-- Images intentionally reuse the local illustrated SVG set so the public site has
-- no third-party image dependency and keeps the lazy-loaded assets cacheable.

insert into post_categories (name, slug, description) values
('性能工程', 'performance-engineering', '围绕加载速度、缓存和可观测性的工程记录'),
('数据库实践', 'database-practice', '从数据建模到查询验证的数据库笔记'),
('写作方法', 'writing-methods', '把日常观察整理成可复读的文字'),
('前端设计', 'frontend-design', '兼顾可读性、动效和无障碍的界面实践'),
('日常观察', 'daily-observation', '记录生活、城市和慢节奏的片段')
on conflict do nothing;

insert into posts (
    slug, title, excerpt, published_date, read_time, category, category_slug, color,
    display_number, featured, status, content, markdown_content, content_format, like_count, views_count
) values
('web-performance-budget', '给个人网站设一份性能预算', '把首屏、图片、脚本和缓存写成可检查的预算，网站就不会在功能增加后悄悄变慢。', '2026-07-31', 8, '性能工程', 'performance-engineering', '#C66A7A', '11', true, 'PUBLISHED', '', $seed$
# 给个人网站设一份性能预算

性能优化不是上线前的一次冲刺，而是每次提交都能回答的几个问题：首屏需要多少字节，用户第一次看到内容要等多久，新增的依赖是否值得。

## 先定义预算

我会为 HTML、关键 CSS、首屏图片和 JavaScript 分别设上限，再用构建产物和真实网络条件复核。预算不是为了追求一个漂亮数字，而是为了让取舍有依据。

## 把预算接入发布流程

构建后检查最大文件、未压缩图片和重复依赖；超过阈值时让发布停下来。只有把检查放在提交附近，性能才不会变成某个人的记忆。

## 让体验优先于指标

骨架、字体和内容顺序要和指标一起看。一个更早出现的可读标题，往往比一个只快了几十毫秒的动画更能改变感受。
$seed$, 'MARKDOWN', 0, 0),
('lazy-loading-with-intent', '懒加载应该由内容决定', '懒加载不是给每张图片都加一个属性，而是让首屏资源、视口距离和内容优先级共同决定加载时机。', '2026-07-30', 7, '性能工程', 'performance-engineering', '#7B6AA8', '12', false, 'PUBLISHED', '', $seed$
# 懒加载应该由内容决定

页面里所有资源都同时请求，浏览器会把带宽花在用户还看不到的内容上。真正有效的懒加载，需要先理解页面的阅读顺序。

## 首屏保持可预测

品牌图、主标题和第一张内容图属于关键路径，应当优先加载并声明尺寸。其余卡片可以使用 `loading="lazy"`，配合明确的宽高避免布局跳动。

## 视口之外延后工作

IntersectionObserver 很适合启动图片、图表和低优先级接口。进入视口前提前一点准备，离开视口后不必反复销毁，体验会比简单的显示隐藏更稳定。

## 记得给失败留出口

图片加载失败要有本地占位和可读的替代文本；懒加载失败不能让正文变成一片空白。
$seed$, 'MARKDOWN', 0, 0),
('cache-headers-that-tell-the-truth', '缓存头要说真话', '静态资源的长期缓存、HTML 的及时更新和接口的短缓存应该各司其职，不能用一个过期时间覆盖全部内容。', '2026-07-29', 6, '性能工程', 'performance-engineering', '#5C8E82', '13', false, 'PUBLISHED', '', $seed$
# 缓存头要说真话

缓存并不会消灭更新问题，它只是把更新时间交给了规则。规则越清楚，发布时越少依赖手工清理。

## 给文件命名版本

带内容哈希的 JavaScript、CSS 和图片可以安全地缓存一年；HTML 和 service worker 则应当及时重新验证。

## 区分公共和私有数据

公开文章列表可以短暂共享缓存，带登录态的个人数据必须避免被公共缓存复用。响应头要表达真实的访问边界。

## 用一次发布验证

发布后检查 `cache-control`、`etag` 和压缩是否生效，比只看构建成功更接近用户实际得到的结果。
$seed$, 'MARKDOWN', 0, 0),
('postgresql-migration-habits', '让数据库迁移可回放', '一份可回放的迁移应该说明前置假设、幂等策略和验证方式，而不只是把一段 SQL 放进版本目录。', '2026-07-28', 9, '数据库实践', 'database-practice', '#4F7892', '14', true, 'PUBLISHED', '', $seed$
# 让数据库迁移可回放

迁移文件是生产系统的一部分。它要面对空数据库、已有数据、重复执行和发布中断，而不是只面对本地开发环境。

## 先写清楚数据形状

字段、索引和约束决定了后续代码能否信任数据。新增内容时，先确认实体模型和读取接口使用的是同一组字段。

## 用冲突策略保证安全重跑

种子数据可以用唯一 slug 配合 `on conflict do nothing`，关系数据则使用稳定的父键查询。这样回滚后重放不会产生重复记录。

## 迁移结束要有查询验收

数量、状态、必填字段和关联记录都应该在部署日志中留下结果。可验证的迁移，才真正可维护。
$seed$, 'MARKDOWN', 0, 0),
('jsonb-or-columns', 'JSONB 和普通列该怎么选', '当数据需要被筛选、排序和约束时，普通列更诚实；当结构确实变化频繁时，再让 JSONB 承担它擅长的部分。', '2026-07-27', 8, '数据库实践', 'database-practice', '#8B6B58', '15', false, 'PUBLISHED', '', $seed$
# JSONB 和普通列该怎么选

JSONB 很灵活，却不是免费的“以后再想”。每个字段的查询方式、约束需求和变更频率，都会影响建模决定。

## 先看查询

需要稳定筛选和排序的字段应尽量成为普通列，并为常用组合建立索引。JSONB 更适合保存不会频繁被单独查询的附加属性。

## 让边界可迁移

即使使用 JSONB，也要在应用层定义版本和默认值。数据从旧结构迁移到新结构时，应该能逐批处理并随时暂停。

## 用真实样本评估

用生产规模的样本跑 `explain analyze`，观察行数估计、索引命中和写入成本，再决定是否抽出字段。
$seed$, 'MARKDOWN', 0, 0),
('writing-from-kitchen-table', '从一张餐桌写出一篇文章', '好文章不一定从宏大主题开始，热气、等待和一次具体的选择就足够成为可靠的入口。', '2026-07-26', 5, '写作方法', 'writing-methods', '#B66F5D', '16', false, 'PUBLISHED', '', $seed$
# 从一张餐桌写出一篇文章

我越来越喜欢从一个可触摸的场景开始写：砧板上的水声、锅边的蒸汽，或者一顿饭结束后还没有收起的碗。

## 先写观察

不要急着解释意义，先记下颜色、动作和时间。具体细节会替抽象观点找到落脚点。

## 再找一条线

把细节放在同一个问题里，例如等待如何改变味道，或者准备如何让人安静。文章只需要一条清楚的线。

## 留一点余白

结尾不必把道理说尽。读者带着自己的经验离开，文字才会在屏幕之外继续生长。
$seed$, 'MARKDOWN', 0, 0),
('small-notes-big-revision', '小笔记也值得认真修订', '修订不是把句子变得华丽，而是删掉不必要的转弯，让真正想说的内容更早出现。', '2026-07-25', 6, '写作方法', 'writing-methods', '#C27B91', '17', false, 'PUBLISHED', '', $seed$
# 小笔记也值得认真修订

一段文字第一次写完时，通常只有材料，还没有节奏。第二次阅读要做的，是帮助读者少走几步路。

## 找出核心句

每一段都应当有一个可以被复述的中心。删掉无法支持它的形容词和重复例子，段落会变得更有呼吸感。

## 把抽象落地

“更高效”“更自然”都需要一个动作或场景来证明。能被看见的细节，比泛泛的判断更有说服力。

## 读出声音

最后朗读一遍，过长的句子和奇怪的标点会自己暴露。保留停顿，也保留作者真正说话的速度。
$seed$, 'MARKDOWN', 0, 0),
('motion-with-a-reason', '让动效解释变化', '自然的动效不靠持续晃动，而是用短暂、克制的运动告诉用户发生了什么以及下一步在哪里。', '2026-07-24', 7, '前端设计', 'frontend-design', '#6B8E9B', '18', false, 'PUBLISHED', '', $seed$
# 让动效解释变化

动效最有价值的时刻，是状态刚刚变化的时候。它应该帮用户理解层级、方向和反馈，而不是抢走内容的注意力。

## 先确定状态

进入、展开、保存和失败是不同语义，不能共用同一种弹跳。先写状态图，再选择持续时间和缓动曲线。

## 让悬停可逆

鼠标离开后，节点回到原位要有连续过渡；拖拽中的元素保持稳定，避免动画和用户输入互相争夺位置。

## 尊重减少动态

`prefers-reduced-motion` 下保留必要的可见反馈，去掉无意义的循环动画。可访问的动效依然可以有温度。
$seed$, 'MARKDOWN', 0, 0),
('content-model-before-component', '先整理内容模型，再写组件', '组件应该呈现稳定的内容结构，而不是替数据格式打补丁；先定义模型，页面才能持续扩展。', '2026-07-23', 8, '前端设计', 'frontend-design', '#7B6B91', '19', true, 'PUBLISHED', '', $seed$
# 先整理内容模型，再写组件

当一个卡片组件同时接收文章、菜谱和搜索结果时，问题通常不在模板，而在数据边界没有被说清楚。

## 把必填字段说清

标题、摘要、链接和图片替代文本属于展示契约；统计字段和管理字段不应悄悄混进同一个对象。

## 为变化留接口

内容模型可以从本地种子迁移到后台接口，但页面依赖的字段名和空状态要稳定。这样替换来源不会重写整个界面。

## 用契约测试守住边界

接口样例、类型和组件测试共同描述真实数据。字段缺失时尽早失败，比渲染出一张看似正常的空卡片更安全。
$seed$, 'MARKDOWN', 0, 0),
('sakura-lake-morning', '湖边樱花开过一整个清晨', '在水面反光、远山和花瓣之间，记录一段不赶时间的散步，也记录影像之外的安静。', '2026-07-22', 4, '日常观察', 'daily-observation', '#D18493', '20', false, 'PUBLISHED', '', $seed$
# 湖边樱花开过一整个清晨

清晨的湖面把天空和山影揉在一起，岸边的樱花并不急着成为风景，它只是随着风轻轻晃动。

## 先放下拍摄目标

不设定必须拍到的画面，注意力会回到光线、温度和脚步。最好的记录常常发生在按下快门之前。

## 记住声音

相片留下颜色，文字可以留下水声、鞋底和远处的鸟鸣。把这些写进当天的短句，回看时才会重新抵达。

## 给一天留一处空白

散步结束后不立刻整理照片，保留一点未完成的感觉。记忆需要时间沉淀，内容也一样。
$seed$, 'MARKDOWN', 0, 0)
on conflict (slug) do nothing;

-- V1 and V34 already publish fifteen posts. Keep this migration's public
-- catalog at twenty by retaining one focused article per new category.
delete from posts where slug in (
    'lazy-loading-with-intent',
    'cache-headers-that-tell-the-truth',
    'jsonb-or-columns',
    'small-notes-big-revision',
    'content-model-before-component'
);

insert into post_tags (post_id, tag, sort_order)
select p.id, v.tag, v.sort_order
from posts p
join (values
('web-performance-budget', '性能预算', 0), ('web-performance-budget', '发布流程', 1),
('lazy-loading-with-intent', '懒加载', 0), ('lazy-loading-with-intent', '前端性能', 1),
('cache-headers-that-tell-the-truth', '缓存', 0), ('cache-headers-that-tell-the-truth', '部署', 1),
('postgresql-migration-habits', 'PostgreSQL', 0), ('postgresql-migration-habits', '迁移', 1),
('jsonb-or-columns', '数据建模', 0), ('jsonb-or-columns', '查询优化', 1),
('writing-from-kitchen-table', '写作', 0), ('writing-from-kitchen-table', '生活', 1),
('small-notes-big-revision', '修订', 0), ('small-notes-big-revision', '记录', 1),
('motion-with-a-reason', '动效', 0), ('motion-with-a-reason', '无障碍', 1),
('content-model-before-component', '内容模型', 0), ('content-model-before-component', '组件契约', 1),
('sakura-lake-morning', '散步', 0), ('sakura-lake-morning', '樱花', 1)
) as v(slug, tag, sort_order) on p.slug = v.slug
on conflict (post_id, sort_order) do nothing;

insert into dish_categories (name, slug, description) values
('家常菜', 'home-style', '适合工作日晚餐的家常做法'),
('面点主食', 'staples', '面条、米饭和发面主食'),
('快手菜', 'quick-meals', '十五到三十分钟即可完成'),
('粤式家常', 'cantonese-home', '清鲜、蒸煮为主的家常风味'),
('甜品饮品', 'desserts', '饭后和下午茶的小份甜味')
on conflict do nothing;

insert into dishes (
    slug, name, summary, category, image_url, image_alt, image_credit, image_source_url,
    prep_minutes, difficulty, rating, featured, published, display_order, favorite_count, views_count, base_servings
) values
('soy-sauce-chicken', '酱油鸡腿', '鸡腿用酱油、姜片和少量冰糖慢慢收汁，皮亮肉嫩，适合一锅完成。', '家常菜', '/food/generated/kung-pao-chicken.svg', '酱油鸡腿与姜片的本地插画', 'Yubai Studio · Original SVG', 'https://hxnf.top/recipes/soy-sauce-chicken', 45, U&'\5BB6\5E38', 4.8, true, true, 11, 0, 0, 2),
('winter-melon-soup', '冬瓜虾皮汤', '冬瓜清甜，虾皮提供鲜味，十几分钟就能煮出一锅轻盈的家常汤。', '快手菜', '/food/generated/lotus-root-soup.svg', '冬瓜虾皮汤的本地插画', 'Yubai Studio · Original SVG', 'https://hxnf.top/recipes/winter-melon-soup', 20, U&'\7B80\5355', 4.6, false, true, 12, 0, 0, 3),
('tea-fragrant-ribs', '茶香排骨', '红茶和排骨一起小火收味，茶香清爽，适合周末提前做好分餐。', '家常菜', '/food/generated/dongpo-pork.svg', '茶香排骨的本地插画', 'Yubai Studio · Original SVG', 'https://hxnf.top/recipes/tea-fragrant-ribs', 65, U&'\8FDB\9636', 4.7, true, true, 13, 0, 0, 3),
('pan-fried-mushroom', '香煎时蔬蘑菇', '蘑菇和芦笋用高温快速煎出焦边，盐、黑胡椒和柠檬汁就足够提味。', '快手菜', '/food/generated/garlic-broccoli.svg', '香煎蘑菇和时蔬的本地插画', 'Yubai Studio · Original SVG', 'https://hxnf.top/recipes/pan-fried-mushroom', 18, U&'\7B80\5355', 4.5, false, true, 14, 0, 0, 2),
('pumpkin-millet-porridge', '南瓜小米粥', '南瓜自然的甜味融进小米粥，早餐提前一晚预约即可得到柔软浓稠的口感。', '家常菜', '/food/generated/tomato-eggs.svg', '南瓜小米粥的本地插画', 'Yubai Studio · Original SVG', 'https://hxnf.top/recipes/pumpkin-millet-porridge', 35, U&'\5BB6\5E38', 4.8, true, true, 15, 0, 0, 3),
('cucumber-shrimp', '黄瓜炒虾仁', '黄瓜清脆，虾仁鲜甜，旺火快炒能保留两种食材的颜色和水分。', '快手菜', '/food/generated/scallion-noodles.svg', '黄瓜炒虾仁的本地插画', 'Yubai Studio · Original SVG', 'https://hxnf.top/recipes/cucumber-shrimp', 16, U&'\7B80\5355', 4.6, false, true, 16, 0, 0, 2),
('scallion-pancake', '葱花发面饼', '发面饼外层微脆、内部松软，葱花和少量芝麻让每一口都有香气。', '面点主食', '/food/generated/handmade-jiaozi.svg', '葱花发面饼的本地插画', 'Yubai Studio · Original SVG', 'https://hxnf.top/recipes/scallion-pancake', 55, U&'\8FDB\9636', 4.7, false, true, 17, 0, 0, 3),
('three-cup-chicken', '三杯鸡', '米酒、酱油和麻油组成浓郁底味，九层塔在最后加入，香气短暂却鲜明。', '家常菜', '/food/generated/sweet-sour-pork.svg', '三杯鸡的本地插画', 'Yubai Studio · Original SVG', 'https://hxnf.top/recipes/three-cup-chicken', 42, U&'\5BB6\5E38', 4.8, true, true, 18, 0, 0, 3),
('miso-salmon', '味噌烤三文鱼', '味噌、蜂蜜和柠檬腌过的三文鱼，烤箱一次完成，适合忙碌工作日。', '粤式家常', '/food/generated/steamed-sea-bass.svg', '味噌烤三文鱼的本地插画', 'Yubai Studio · Original SVG', 'https://hxnf.top/recipes/miso-salmon', 30, U&'\5BB6\5E38', 4.7, false, true, 19, 0, 0, 2),
('red-bean-rice-cake', '红豆米糕', '浸泡后的糯米和红豆蒸成柔软米糕，甜味克制，适合下午茶切小块分享。', '甜品饮品', '/food/generated/mapo-tofu.svg', '红豆米糕的本地插画', 'Yubai Studio · Original SVG', 'https://hxnf.top/recipes/red-bean-rice-cake', 80, U&'\8FDB\9636', 4.6, false, true, 20, 0, 0, 4)
on conflict (slug) do nothing;

insert into dish_ingredients (dish_id, ingredient, sort_order)
select d.id, v.ingredient, v.sort_order
from dishes d join (values
('soy-sauce-chicken', '带皮鸡腿 2 只', 0), ('soy-sauce-chicken', '生抽 45 毫升、老抽 10 毫升', 1), ('soy-sauce-chicken', '姜片、冰糖和葱段适量', 2),
('winter-melon-soup', '冬瓜 500 克', 0), ('winter-melon-soup', '虾皮 15 克', 1), ('winter-melon-soup', '姜丝、白胡椒和葱花适量', 2),
('tea-fragrant-ribs', '猪肋排 600 克', 0), ('tea-fragrant-ribs', '红茶 8 克、黄酒 50 毫升', 1), ('tea-fragrant-ribs', '生抽、冰糖和葱姜适量', 2),
('pan-fried-mushroom', '口蘑 250 克', 0), ('pan-fried-mushroom', '芦笋 150 克', 1), ('pan-fried-mushroom', '橄榄油、黑胡椒和柠檬半个', 2),
('pumpkin-millet-porridge', '小米 100 克', 0), ('pumpkin-millet-porridge', '南瓜 250 克', 1), ('pumpkin-millet-porridge', '清水 1200 毫升、少量盐', 2),
('cucumber-shrimp', '鲜虾仁 250 克', 0), ('cucumber-shrimp', '黄瓜 2 根', 1), ('cucumber-shrimp', '蒜末、料酒和白胡椒适量', 2),
('scallion-pancake', '中筋面粉 350 克', 0), ('scallion-pancake', '葱花 100 克', 1), ('scallion-pancake', '酵母、芝麻和食用油适量', 2),
('three-cup-chicken', '鸡腿肉 450 克', 0), ('three-cup-chicken', '米酒、生抽和麻油各 50 毫升', 1), ('three-cup-chicken', '九层塔、姜片和蒜瓣适量', 2),
('miso-salmon', '三文鱼排 2 块', 0), ('miso-salmon', '白味噌 40 克、蜂蜜 20 克', 1), ('miso-salmon', '柠檬汁和黑胡椒适量', 2),
('red-bean-rice-cake', '糯米 300 克', 0), ('red-bean-rice-cake', '红豆 120 克', 1), ('red-bean-rice-cake', '红糖 50 克、桂花少量', 2)
) as v(slug, ingredient, sort_order) on d.slug = v.slug
on conflict (dish_id, sort_order) do nothing;

insert into dish_steps (dish_id, instruction, sort_order)
select d.id, v.instruction, v.sort_order
from dishes d join (values
('soy-sauce-chicken', '鸡腿擦干后煎至表皮金黄，加入姜片和葱段。', 0), ('soy-sauce-chicken', '倒入酱油、清水和冰糖，小火焖二十五分钟后收汁。', 1),
('winter-melon-soup', '冬瓜去皮切块，和姜丝一起放入沸水中煮八分钟。', 0), ('winter-melon-soup', '加入虾皮和白胡椒，再煮五分钟，出锅撒葱花。', 1),
('tea-fragrant-ribs', '排骨冷水下锅焯去浮沫，红茶用热水泡开备用。', 0), ('tea-fragrant-ribs', '排骨煎香后加入茶汤和调味料，小火焖四十分钟再收汁。', 1),
('pan-fried-mushroom', '蘑菇切厚片，芦笋去老根并擦干水分。', 0), ('pan-fried-mushroom', '热锅少油煎至两面焦香，关火后挤入柠檬汁。', 1),
('pumpkin-millet-porridge', '小米洗净，南瓜切小块，和清水一起入锅。', 0), ('pumpkin-millet-porridge', '大火煮开后转小火三十分钟，搅拌至粥体顺滑。', 1),
('cucumber-shrimp', '虾仁用料酒和白胡椒腌十分钟，黄瓜切菱形片。', 0), ('cucumber-shrimp', '先炒虾仁至变色，再放蒜末和黄瓜旺火翻炒两分钟。', 1),
('scallion-pancake', '面粉加酵母和温水揉匀，发酵至两倍大后擀开。', 0), ('scallion-pancake', '抹油撒葱花卷起，擀成饼后小火两面烙至金黄。', 1),
('three-cup-chicken', '鸡腿肉切块煎出油脂，加入姜片和蒜瓣炒香。', 0), ('three-cup-chicken', '倒入米酒和生抽焖十五分钟，收汁前加入麻油与九层塔。', 1),
('miso-salmon', '味噌、蜂蜜和柠檬汁调匀，均匀涂在三文鱼表面。', 0), ('miso-salmon', '烤箱二百度烤十二至十五分钟，表面微焦即可。', 1),
('red-bean-rice-cake', '糯米和红豆分别浸泡一晚，红豆加红糖煮至软烂。', 0), ('red-bean-rice-cake', '模具铺糯米和红豆分层，上锅蒸四十五分钟，冷却后切块。', 1)
) as v(slug, instruction, sort_order) on d.slug = v.slug
on conflict (dish_id, sort_order) do nothing;
