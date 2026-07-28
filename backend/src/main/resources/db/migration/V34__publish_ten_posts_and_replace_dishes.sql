-- Replace the demo recipe catalog while preserving kitchen history snapshots through ON DELETE SET NULL.
delete from dishes;
delete from dish_categories;

insert into dish_categories (name, slug, description) values
('川味经典', '川味经典', '麻辣鲜香、层次分明的川味代表菜。'),
('周末料理', '周末料理', '适合留出时间慢慢炖煮和分享的菜。'),
('面点主食', '面点主食', '面、饺子和一碗就能满足的主食。'),
('十分钟菜', '十分钟菜', '步骤简洁、适合工作日快速完成。'),
('粤式家常', '粤式家常', '清鲜、酸甜与蒸制为主的家常风味。');

insert into dishes (
    slug, name, summary, category, image_url, image_alt, image_credit, image_source_url,
    prep_minutes, difficulty, rating, featured, published, display_order, favorite_count, views_count, base_servings
) values
('authentic-mapo-tofu', '麻婆豆腐', '豆腐吸满红亮汤汁，花椒的麻与豆瓣的香在热气里展开。', '川味经典', '/food/generated/mapo-tofu.svg', '红油与青蒜点缀的麻婆豆腐插画', 'Yubai Studio · Original', 'https://hxnf.top/recipes/authentic-mapo-tofu', 28, '家常', 4.9, true, true, 1, 0, 0, 2),
('kung-pao-chicken', '宫保鸡丁', '鸡丁、花生和干辣椒在大火中交汇，酸甜之后留下一点椒麻。', '川味经典', '/food/generated/kung-pao-chicken.svg', '鸡丁花生与辣椒组成的宫保鸡丁插画', 'Yubai Studio · Original', 'https://hxnf.top/recipes/kung-pao-chicken', 25, '家常', 4.8, true, true, 2, 0, 0, 2),
('dongpo-pork', '东坡肉', '用时间换来的软糯口感，酱油、冰糖与黄酒收成温润光泽。', '周末料理', '/food/generated/dongpo-pork.svg', '砂锅中红亮方正的东坡肉插画', 'Yubai Studio · Original', 'https://hxnf.top/recipes/dongpo-pork', 95, '进阶', 4.9, true, true, 3, 0, 0, 4),
('lotus-root-pork-rib-soup', '莲藕排骨汤', '莲藕粉糯、排骨清香，一锅温和汤水适合慢慢等待。', '周末料理', '/food/generated/lotus-root-soup.svg', '陶锅中的莲藕排骨汤插画', 'Yubai Studio · Original', 'https://hxnf.top/recipes/lotus-root-pork-rib-soup', 80, '家常', 4.7, false, true, 4, 0, 0, 4),
('handmade-jiaozi', '手工水饺', '从和面、调馅到捏合，每一只饺子都保留手作的温度。', '面点主食', '/food/generated/handmade-jiaozi.svg', '竹盘中排列的手工水饺插画', 'Yubai Studio · Original', 'https://hxnf.top/recipes/handmade-jiaozi', 70, '进阶', 4.8, false, true, 5, 0, 0, 4),
('scallion-oil-noodles', '葱油拌面', '焦香葱油裹住细面，简单调味也能有清晰层次。', '面点主食', '/food/generated/scallion-noodles.svg', '青葱与酱色细面的葱油拌面插画', 'Yubai Studio · Original', 'https://hxnf.top/recipes/scallion-oil-noodles', 18, '简单', 4.6, false, true, 6, 0, 0, 2),
('garlic-broccoli', '蒜香西兰花', '脆嫩西兰花只用蒜与盐提味，十分钟端上一盘清爽。', '十分钟菜', '/food/generated/garlic-broccoli.svg', '翠绿西兰花与蒜片插画', 'Yubai Studio · Original', 'https://hxnf.top/recipes/garlic-broccoli', 12, '简单', 4.5, false, true, 7, 0, 0, 2),
('tomato-scrambled-eggs', '番茄炒蛋', '酸甜番茄和柔嫩鸡蛋，是忙碌日子里可靠的家常味。', '十分钟菜', '/food/generated/tomato-eggs.svg', '红番茄与金黄炒蛋插画', 'Yubai Studio · Original', 'https://hxnf.top/recipes/tomato-scrambled-eggs', 15, '简单', 4.7, false, true, 8, 0, 0, 2),
('sweet-sour-pork', '菠萝咕咾肉', '酥脆肉块裹上酸甜亮汁，与菠萝和彩椒组成明快味道。', '粤式家常', '/food/generated/sweet-sour-pork.svg', '菠萝彩椒与酥肉组成的咕咾肉插画', 'Yubai Studio · Original', 'https://hxnf.top/recipes/sweet-sour-pork', 40, '家常', 4.6, false, true, 9, 0, 0, 3),
('steamed-sea-bass', '清蒸鲈鱼', '姜葱与热油衬出鱼肉清甜，火候比调料更重要。', '粤式家常', '/food/generated/steamed-sea-bass.svg', '长盘中的葱姜清蒸鲈鱼插画', 'Yubai Studio · Original', 'https://hxnf.top/recipes/steamed-sea-bass', 30, '家常', 4.8, true, true, 10, 0, 0, 3);

insert into dish_ingredients (dish_id, ingredient, sort_order)
select d.id, v.ingredient, v.sort_order from dishes d join (values
('authentic-mapo-tofu','嫩豆腐 400 克',0),('authentic-mapo-tofu','牛肉末 80 克',1),('authentic-mapo-tofu','豆瓣酱、花椒与蒜苗适量',2),
('kung-pao-chicken','鸡腿肉 300 克',0),('kung-pao-chicken','熟花生 60 克',1),('kung-pao-chicken','干辣椒、香醋与大葱适量',2),
('dongpo-pork','带皮五花肉 600 克',0),('dongpo-pork','绍兴黄酒 350 毫升',1),('dongpo-pork','酱油、冰糖与葱姜适量',2),
('lotus-root-pork-rib-soup','排骨 500 克',0),('lotus-root-pork-rib-soup','莲藕 500 克',1),('lotus-root-pork-rib-soup','姜片、盐与白胡椒适量',2),
('handmade-jiaozi','中筋面粉 400 克',0),('handmade-jiaozi','猪肉馅 300 克',1),('handmade-jiaozi','白菜、葱姜水与香油适量',2),
('scallion-oil-noodles','细面 250 克',0),('scallion-oil-noodles','小葱 100 克',1),('scallion-oil-noodles','生抽、老抽与糖适量',2),
('garlic-broccoli','西兰花 1 颗',0),('garlic-broccoli','大蒜 4 瓣',1),('garlic-broccoli','盐与食用油适量',2),
('tomato-scrambled-eggs','番茄 2 个',0),('tomato-scrambled-eggs','鸡蛋 3 个',1),('tomato-scrambled-eggs','盐、糖与葱花适量',2),
('sweet-sour-pork','猪里脊 350 克',0),('sweet-sour-pork','菠萝 150 克',1),('sweet-sour-pork','彩椒、番茄酱与白醋适量',2),
('steamed-sea-bass','鲜鲈鱼 1 条',0),('steamed-sea-bass','姜丝与葱丝适量',1),('steamed-sea-bass','蒸鱼豉油与热油适量',2)
) as v(slug, ingredient, sort_order) on d.slug = v.slug;

insert into dish_steps (dish_id, instruction, sort_order)
select d.id, v.instruction, v.sort_order from dishes d join (values
('authentic-mapo-tofu','豆腐切块焯水，炒香肉末和豆瓣酱。',0),('authentic-mapo-tofu','加入高汤和豆腐轻推入味，勾芡后撒花椒与蒜苗。',1),
('kung-pao-chicken','鸡丁腌十分钟，调好酸甜碗汁。',0),('kung-pao-chicken','大火炒鸡丁与辣椒，倒汁收浓后拌入花生。',1),
('dongpo-pork','五花肉焯水切块，砂锅铺葱姜。',0),('dongpo-pork','加入黄酒酱油和冰糖，小火焖至软糯收汁。',1),
('lotus-root-pork-rib-soup','排骨焯水，莲藕去皮切滚刀块。',0),('lotus-root-pork-rib-soup','加足热水与姜片，小火炖七十分钟后调味。',1),
('handmade-jiaozi','揉面醒发，白菜杀水后与肉馅拌匀。',0),('handmade-jiaozi','擀皮包馅，沸水下锅煮至饺子鼓起浮面。',1),
('scallion-oil-noodles','小火将葱段炸至焦黄，加入酱油和糖。',0),('scallion-oil-noodles','面条煮熟沥水，趁热拌入葱油汁。',1),
('garlic-broccoli','西兰花切朵焯水四十秒。',0),('garlic-broccoli','炒香蒜末，加入西兰花大火翻炒调味。',1),
('tomato-scrambled-eggs','鸡蛋炒至蓬松盛出，番茄炒软出汁。',0),('tomato-scrambled-eggs','鸡蛋回锅快速翻匀，用盐和少量糖调味。',1),
('sweet-sour-pork','里脊腌制裹粉炸脆，彩椒与菠萝切块。',0),('sweet-sour-pork','熬浓酸甜汁，倒入全部材料快速裹匀。',1),
('steamed-sea-bass','鲈鱼处理干净，铺姜后旺火蒸八分钟。',0),('steamed-sea-bass','倒掉蒸汁，铺葱丝，淋豉油和热油。',1)
) as v(slug, instruction, sort_order) on d.slug = v.slug;

insert into posts (
    slug, title, excerpt, published_date, read_time, category, category_slug, color,
    display_number, featured, status, content, markdown_content, content_format, like_count, views_count
) values
('spring-boot-transaction-boundaries','Spring Boot 事务边界：从注解走向真实一致性','从代理、自调用、异常传播和数据库提交出发，梳理事务真正生效的边界。','2026-07-29',9,'工程实践','工程实践','#315C73','01',true,'PUBLISHED','',$md$
# Spring Boot 事务边界

事务不是写上 `@Transactional` 就结束了。真正的边界由代理调用、连接生命周期和异常传播共同决定。

![服务、代理与数据库事务边界示意图](/images/articles/transaction-boundaries.svg)

## 从调用路径开始排查

同类内部调用绕过代理，是最常见的失效原因。先画出入口、服务和仓储的调用方向，再决定注解应该放在哪里。

## 让失败保持可见

不要吞掉异常后期待事务自动回滚。为业务错误定义清晰语义，并用集成测试确认提交后的数据库状态。

## 最后检查外部副作用

数据库回滚不会撤销已经发送的消息或文件。需要可靠一致性时，应使用 outbox 或补偿流程，而不是扩大一个本地事务。
$md$,'MARKDOWN',0,0),
('vue-composable-contracts','Vue Composable 不只是复用：为状态写下契约','用输入、输出和生命周期约束组合式函数，让复用从代码片段变成稳定边界。','2026-07-28',7,'工程实践','工程实践','#4F6F52','02',false,'PUBLISHED','',$md$
# 为 Composable 写下契约

一个好用的 Composable 不只减少重复，它还说明状态由谁拥有、何时变化、何时释放。

![Vue 组合式函数输入输出关系图](/images/articles/vue-contracts.svg)

## 输入应当明确

优先接收 `Ref` 或 getter，并说明是否允许在运行时变化。不要在内部悄悄读取全局状态。

## 输出保持最小

暴露只读状态和少量意图明确的动作。调用方不应该知道请求序号、定时器和清理句柄等实现细节。

## 生命周期也属于 API

订阅、事件和异步任务必须在作用域结束时清理。可复用的前提不是短，而是行为可预测。
$md$,'MARKDOWN',0,0),
('postgresql-index-reading','看懂 PostgreSQL 索引：从查询计划找到真正瓶颈','借助执行计划理解扫描、选择性和真实成本，而不是看到慢查询就立刻加索引。','2026-07-27',10,'工程实践','工程实践','#6B5B95','03',true,'PUBLISHED','',$md$
# 看懂 PostgreSQL 索引

索引解决的是访问路径问题，不是所有性能问题。第一步永远是读取真实执行计划。

![PostgreSQL 查询计划与索引路径示意](/images/articles/postgres-index.svg)

## 先看实际行数

估算行数与实际行数差距很大时，优化器正在基于错误信息做决定。此时应先更新统计信息或检查数据分布。

## 选择性决定价值

低选择性列上的单列索引往往帮助有限。组合索引的顺序应来自稳定查询模式，而不是字段的重要程度。

## 写入成本不能忽略

每个索引都增加插入、更新和维护成本。上线前记录基线，上线后验证命中和总体吞吐。
$md$,'MARKDOWN',0,0),
('safe-markdown-pipeline','一篇 Markdown 如何安全抵达浏览器','从编辑器、数据库到受控渲染，梳理 Markdown 内容管线中的安全边界。','2026-07-26',8,'工程实践','工程实践','#A65F46','04',false,'PUBLISHED','',$md$
# Markdown 如何安全抵达浏览器

Markdown 是源文本，不等于可信 HTML。安全策略必须覆盖解析、扩展和最终 DOM。

![Markdown 从数据库到浏览器的渲染管线](/images/articles/markdown-pipeline.svg)

## 保存原文而不是结果

原文便于迁移和重新渲染，HTML 快照只适合作为缓存或兼容数据。

## 扩展需要白名单

图片、链接和代码块都应经过协议与属性约束。不要因为内容来自后台就跳过防护。

## 在最终边界验证

后端消毒与前端受控渲染可以分担风险，但规则必须有测试，避免升级解析器后安全边界漂移。
$md$,'MARKDOWN',0,0),
('designing-calm-admin-tools','安静的后台：让内容管理不打断写作','通过信息层级、默认值和渐进式操作，让元数据管理服务于写作而不是争夺注意力。','2026-07-25',6,'设计札记','设计札记','#8A6D3B','05',false,'PUBLISHED','',$md$
# 安静的后台

内容后台的任务不是展示功能数量，而是帮助作者稳定地完成一次写作和发布。

![低干扰内容管理后台界面](/images/articles/calm-admin.svg)

## 先完成主要任务

标题、正文和状态应该形成清晰主线。低频设置可以收纳，但不能隐藏关键风险。

## 默认值表达工作流

新内容默认草稿、路由可自动生成、分类来自受控列表，这些默认值能减少错误而不是减少自由。

## 反馈需要靠近动作

保存、冲突和校验信息应出现在用户刚刚操作的位置，并明确下一步如何恢复。
$md$,'MARKDOWN',0,0),
('accessible-motion-design','动效也需要边界：为所有人设计可理解的变化','让动效解释状态变化，同时尊重减少动态偏好、键盘操作和认知负担。','2026-07-24',7,'设计札记','设计札记','#3F718C','06',false,'PUBLISHED','',$md$
# 动效也需要边界

动效的价值在于解释空间和状态，而不是证明页面可以移动。

![界面动效节奏与减少动态偏好对比](/images/articles/accessible-motion.svg)

## 每段运动都要有理由

进入、离开、排序和反馈是不同语义。先说明变化，再决定持续时间和缓动。

## 尊重系统偏好

`prefers-reduced-motion` 不应只关闭一处动画。页面滚动、视差和自动播放都需要替代方案。

## 不让动画阻塞操作

焦点顺序和点击区域应立即可用。视觉过渡不能成为等待下一步的门槛。
$md$,'MARKDOWN',0,0),
('personal-knowledge-garden','把知识库种成花园，而不是堆成仓库','用链接、主题和持续修订，让笔记从囤积的信息变成可生长的知识结构。','2026-07-23',6,'设计札记','设计札记','#758E67','07',false,'PUBLISHED','',$md$
# 把知识库种成花园

收藏更多资料不会自动带来理解。知识需要被重新表达、连接和使用。

![由笔记节点组成的个人知识花园](/images/articles/knowledge-garden.svg)

## 从问题开始记录

一条笔记最好回答一个具体问题。来源可以很多，但自己的结论必须清楚。

## 连接比目录更重要

目录负责找到内容，链接负责产生新关系。为概念建立双向入口，知识才会跨主题流动。

## 定期修剪

合并重复内容、删除失效结论、补上新的证据。维护不是整理卫生，而是继续思考。
$md$,'MARKDOWN',0,0),
('city-walk-light-notes','城市散步时，我怎样记录光线','一次不设目的地的散步，以及如何用相机和文字记住城市中短暂的光。','2026-07-22',5,'日常观察','日常观察','#B97850','08',false,'PUBLISHED','',$md$
# 城市散步时记录光线

不设目的地之后，注意力才从路线回到街道本身。

![傍晚街道上落在建筑之间的光线](/images/articles/city-light.svg)

## 先观察再举起相机

光从哪里来、停在哪里、几分钟后会消失，这些判断比参数更接近照片的核心。

## 记录画面之外的事

写下声音、温度和当时的犹豫。照片保存形状，文字保存选择。

## 接受没有作品的散步

不是每次外出都需要产出。持续观察本身会改变下一次看见城市的方式。
$md$,'MARKDOWN',0,0),
('slow-weekend-cooking','周末慢慢做一顿饭，重新理解时间','在切菜、等待和收拾之间，重新发现那些不能被效率指标衡量的时间。','2026-07-21',5,'日常观察','日常观察','#9B6547','09',false,'PUBLISHED','',$md$
# 周末慢慢做一顿饭

平日里做饭追求迅速，周末则可以把等待重新放回过程。

![自然光下准备中的周末餐桌](/images/articles/weekend-cooking.svg)

## 选择需要时间的菜

炖煮、醒面和腌制让人无法持续加速。等待不是空白，而是味道形成的一部分。

## 让准备可见

提前摆好食材和器具，做饭会从应付任务变成有节奏的动作。

## 收拾也是结束仪式

洗净锅具、擦干台面、留下一小份明天吃。完整结束会让这段时间真正属于自己。
$md$,'MARKDOWN',0,0),
('one-year-independent-site','独立网站一年：留下什么，也舍弃什么','从技术选择、内容节奏到主动删减，复盘维护一个独立网站一年来的得失。','2026-07-20',8,'日常观察','日常观察','#596B7A','10',false,'PUBLISHED','',$md$
# 独立网站一年

维护独立网站的意义，不只是拥有一个网址，而是持续决定什么值得留下。

![桌面上的独立网站年度记录与手稿](/images/articles/independent-site.svg)

## 技术应该逐渐安静

基础设施稳定后，最好的状态是很少被注意。自动备份、迁移和监控比频繁换框架更重要。

## 节奏比数量可靠

固定回来看、修订和发布，比短时间堆积内容更能形成站点气质。

## 主动舍弃

删除没人使用的功能，合并重复栏目，拒绝没有维护能力的承诺。边界让网站能够继续生长。
$md$,'MARKDOWN',0,0);

insert into post_tags (post_id, tag, sort_order)
select p.id, v.tag, v.sort_order from posts p join (values
('spring-boot-transaction-boundaries','Spring Boot',0),('spring-boot-transaction-boundaries','事务',1),
('vue-composable-contracts','Vue',0),('vue-composable-contracts','TypeScript',1),
('postgresql-index-reading','PostgreSQL',0),('postgresql-index-reading','性能',1),
('safe-markdown-pipeline','Markdown',0),('safe-markdown-pipeline','安全',1),
('designing-calm-admin-tools','后台设计',0),('designing-calm-admin-tools','内容管理',1),
('accessible-motion-design','动效',0),('accessible-motion-design','无障碍',1),
('personal-knowledge-garden','知识管理',0),('personal-knowledge-garden','写作',1),
('city-walk-light-notes','摄影',0),('city-walk-light-notes','城市',1),
('slow-weekend-cooking','烹饪',0),('slow-weekend-cooking','生活',1),
('one-year-independent-site','独立网站',0),('one-year-independent-site','复盘',1)
) as v(slug, tag, sort_order) on p.slug = v.slug;
