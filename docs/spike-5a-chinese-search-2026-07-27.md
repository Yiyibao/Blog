# Spike · 5A 中文全文检索选型（2026-07-27）

结论：**不引入 zhparser/pgroonga，采用计划书既定回退路径——pg_trgm（V18 已建）+ 加权相关性排序，LIKE 召回面保留为降级兜底。**

## 1. 判定依据

1. **生产不可控**：zhparser/pgroonga 均为 PG 服务器端扩展，需在生产服务器安装系统级软件包（编译或 apt 源）并 `CREATE EXTENSION`——属「需用户服务器操作」范畴，按当前执行约定跳过；且生产 PG 版本/发行渠道未知，扩展二进制兼容性无法在本侧验证。
2. **测试与 CI 链路不含扩展**：验证链路统一走 Testcontainers `postgres:17` 官方镜像（P2-4），该镜像不含 zhparser/pgroonga；引入需自定义镜像（新增一条供应链维护面）且与 CI 锁版策略冲突。
3. **PG 内建 tsvector 对中文无益**：default/simple parser 不分词，中文整段成单 token，`to_tsquery` 词组召回近乎为零——tsvector+GIN 路线在无分词扩展时对中文反而是负收益，故不做「V23+ tsvector 生成列」迁移（该取号已被 4C/4D 占用，台账同步更新）。

## 2. 回退实现（已落地）

- **召回**：维持 LIKE 子串匹配（title/excerpt/category/content/markdownContent/tags）。中文场景下子串匹配即天然「词组精确召回」——查询「独角鲸」命中所有含该词组的文本，无分词歧义；P0-9 通配符转义不变。V18 的 pg_trgm GIN 索引继续服务 LIKE 加速。
- **排序（本批新增）**：加权相关性 `score = 标题4 + 摘要2 + 分类2 + 标签2 + 正文1`，JPQL CASE 表达式列 + `JpaSort.unsafe("score")` 降序、同分最新优先；缺省排序从 date_desc 改为 relevance，显式 `sort=DATE_DESC/DATE_ASC` 行为不变（L-8 契约向后兼容）。
- **查询形态优化**：标签条件 EXISTS 化，去掉 LEFT JOIN + DISTINCT（每篇一行，score 不受标签行复制干扰，count 同步简化）。
- **前端高亮**：`splitHighlight` 纯文本分段 + `<mark>` 模板插值渲染——不走 v-html，源文本中的 HTML 保持纯文本，无 XSS 面（有测试）。

## 3. 召回对比留档

| 场景 | LIKE 子串（现方案） | tsvector + simple parser | zhparser（未采用） |
| --- | --- | --- | --- |
| 中文词组「独角鲸」 | ✅ 精确子串召回 | ❌ 不分词整段 token，无法命中 | ✅（需服务器扩展） |
| 词序变体「导航手册」vs「手册导航」 | 按字面子串各自召回（无歧义扩展） | ❌ | ✅ 可分词交叉召回 |
| 英文单词 | ✅ 子串（含词内命中） | ✅ 词级 | ✅ |
| 排序 | ✅ 加权字段相关性（本批） | ts_rank（不可用） | ts_rank |

验证证据：IT @Order(59)——标题命中旧文排在仅正文命中新文之前（relevance 缺省），显式 date_desc 仍最新优先；两篇均被召回（中文词组召回面完整）。

## 4. 复活条件

若未来用户在生产完成 zhparser/pgroonga 安装（阶段 6 运维窗口），tsvector 生成列迁移（届时按台账取号）+ ts_rank/ts_headline 可作为增量替换排序与摘要高亮层，LIKE 降级路径与本批加权排序保持不动。
