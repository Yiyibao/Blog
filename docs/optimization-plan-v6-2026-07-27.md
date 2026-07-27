# 余白博客 · 持续优化计划 v6

> **定位**：接替 v5，在 5C 后端+前端全部完成后成为唯一执行依据。
>
> **当前基线（2026-07-27）**：后端 379/379、前端 353/353、typecheck+build 全绿；CI 自 dd97dd1 起全绿。
>
> **配套提示词**：`.agents/V6-FRONTEND-AGENT.md`、`.agents/V6-BACKEND-AGENT.md`、`.agents/V6-ACCEPTANCE-AGENT.md`。

---

## 一、完成度台账

| 条目 | 状态 | 凭据 |
|------|------|-------|
| 阶段 0~4 全部 | ✅ 已完成 | checkpoint 在档 |
| 5A 中文检索 | ✅ 已完成 | 8eb31c4 + docs/spike-5a |
| 5B 标签一等公民 | ✅ 已完成 | 2dbb91e |
| 5C 图谱增强 | ✅ 已完成 | 后端子图端点+缓存/Vary 头；前端双击/展开/去重/竞态/自动局部模式 |
| 5D 相关推荐 | ✅ 已完成 | 60d8767 |
| P2-7 .gitattributes + renormalize | ✅ 已完成 | 5ac96d8 |
| NF-10 styles.css 拆分 | ✅ 已完成 | 4035fdf |
| NB-9 演示数据分离 | ⏭ 已跳过 | 需用户在存量库执行 flyway repair |
| NF-12 base_servings | ✅ 已完成 | b9931e9 |
| 6A 应用侧监控 | ✅ 已完成 | Prometheus 端点仅 ADMIN 可读；后端 386/386 |
| 6A 部署侧监控 | ⏭ 已跳过 | 内网暴露与 PostgreSQL 慢查询配置需服务器操作 |
| 6B 应用侧对象存储 | ✅ 已完成 | V27 + 安全本地存储边界 + bytea 惰性迁移；后端 408/408 |
| 6B 部署侧对象存储 | ⏭ 已跳过 | nginx/S3、生产文件搬迁与凭据需服务器操作 |

## 二、总路线图

| 阶段 | 主题 | 核心产出 |
|------|------|----------|
| 1 | 收尾挂起项 | 5D 相关推荐、P2-7 renormalize、NF-10 样式拆分 |
| 2 | 平台化运维 | 监控告警、对象存储、多用户令牌、备份演练、SEO 预渲染 |
| 常态 | 贯穿机制 | 性能预算、依赖升级、安全复审 |

## 三、阶段 1 明细

### 3.1 5D 相关推荐（后端 1 天 + 前端 1 天）

后端：详情响应附共享标签 TOP3~5 文章（聚合查询 + Caffeine 缓存）；无共享标签隐藏区块（不返回空列表）。

前端：文章详情底部推荐卡片（ArticlePage.vue），登录/游客均可见；根治客户端窗口局限（非前 N 条摘要上限内检索）。

测试：后端单测（有共享标签 TOP3+缓存/无标签隐藏）；前端组件测试无共享标签→隐藏。

### 3.2 P2-7 renormalize + .gitattributes（0.5 天，全栈）

新增 `.gitattributes`：`* text=auto eol=lf`；图片/字体 Binary。
一次显式 `git add --renormalize .` + 独立提交。
前端在 renormalize 之后进行 NF-10 拆分，避免 CRLF 噪音干扰 diff。
`.gitignore` 补遗（如 `_to_delete/`、`outputs/`）。

### 3.3 NF-10 styles.css 拆分（1 天，前端）

拆五个文件：tokens/公共/文章笔记/美食/后台；后台样式随路由懒加载。
首屏 CSS 体积对比留档（基线 115KB）。

### 3.4 NB-9 演示数据分离（0.5 天，后端，待用户决策）

新库 seed profile 分离，存量库不动；移除 V1/V6/V13/V14 内嵌种子。
用户需执行 `flyway repair`。

### 3.5 NF-12 base_servings（0.5 天，双端，若美食专项未完成）

新迁移加 dishes.base_servings；前端按其缩放份量。

## 四、阶段 2 明细（长期迭代）

| 项 | 内容 |
|----|------|
| 6A 监控告警 | micrometer-prometheus 暴露 /actuator/prometheus（仅内网）；PG 慢查询 |
| 6B 对象存储 | bytea → 本地磁盘/S3 + nginx 直出；旧 UUID 路径 301 |
| 6C 多用户&令牌 | refresh token；roles 去硬编码；TOTP 两步验证 |
| 6D SEO 预渲染 | vite-ssg 或 nginx 按爬虫 UA 注入 meta |
| 6E 备份演练 | 每季度干净库完整恢复；加异机/对象存储副本 |
| 6F 依赖升级 | 每月 audit；每季 minor；major 单独评审 |

## 五、执行顺序

1. **5D 相关推荐**（后端先落 API → 前端消费 → checkpoint）
2. **P2-7 + .gitattributes**（独立提交，renormalize）
3. **NF-10 样式拆分**（renormalize 之后，diff 干净）
4. NB-9 / NF-12（等待用户决策）
5. 阶段 2 按需启动

## 六、配套提示词

见 `.agents/V6-*-AGENT.md`（随本文同步修订）。
