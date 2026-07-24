# 余白博客 · 功能拓展与优化方案 v2

> 基线：Sprint 0（CI）+ Sprint 1/3/4/5 已完成
> 基于完整代码审计和架构分析

---

## 一、现状总览

### 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| 前端框架 | Vue 3 + TypeScript | 3.5.22 / 5.9.3 |
| 构建 | Vite + Rolldown | 8.1.5 |
| 测试 | Vitest + Vue Test Utils | 4.1.10 |
| 编辑器 | TiP Tap (ProseMirror) | 3.28 |
| 后端框架 | Spring Boot | 3.5.16 |
| ORM | Hibernate + Spring Data JPA | 6.6.53 |
| 数据库 | PostgreSQL | 17+（当前 18.4） |
| 迁移 | Flyway | 最新（8 migrations） |
| 安全 | Spring Security + Nimbus JWT + BCrypt | — |
| CI | GitHub Actions | 已建立 |

### 已有的优势

- 前端测试基础扎实（34 tests，含异步竞态、可控 Promise 队列、fake timers）
- 后端测试已补全（52 tests，含 42 个 Service 单元测试）
- 深色模式 CSS 变量体系已完整建立
- 路由已拆分为独立 Page 组件（10 个懒加载页面）
- Pinia store 已引入（auth / content / ui）
- PWA 已配置（Service Worker + manifest）
- 图片上传自动压缩（JPEG 1920px max, quality 85%）
- 管理仪表盘统计端点已建立
- CI 质量门禁已运行（typecheck + test + build + npm audit + git diff --check）

---

## 二、技术债务

### P0 — 剩余类型安全漏洞

| 文件 | 问题 | 风险 |
|------|------|------|
| `frontend/src/composables/useSearch.ts:27,29` | `(err as any)` 用于 catch 分支 | 类型安全降级 |
| `frontend/src/test/*.test.ts`（8 处） | `any` 用于 mock 队列 | 测试维护性 |
| `frontend/src/api/admin.ts:99` | `as any[]` 类型断言 | 无编译期检查 |

**修复方案**：为 catch 分支定义 `SearchError` 类型，mock 队列使用泛型参数。

### P1 — CORS 配置宽松

`backend/.../config/WebConfiguration.java` — `allowedOriginPatterns("*")`，意味着任意来源可以跨域访问 API。虽然 dev 环境方便，但生产环境应限制。

**修复方案**：
```java
// 生产环境：从配置文件读取，默认宽限本地开发
@Value("${app.cors.allowed-origins:http://localhost:5173}")
private String[] allowedOrigins;
```

### P1 — 管理端缺少退出登录后的状态清理

当前 `AdminDashboard.vue` 的 `logout()` 调用 `clearAdminSession()` + `router.replace('/admin/login')`，但 admin axios 实例的拦截器仍可能持有过期拦截逻辑。建议在 store 中添加全局复位方法。

### P2 — Flyway 版本命名不一致

Migration 文件使用 `V{number}__`（单下划线 + 双下划线），Flyway 标准是 `V{number}__` 完全可以工作，但描述部分因多了一个下划线命名不一致。示例：`V1__create_blog_schema.sql` 正确解析为版本 `1` + 描述 `create_blog_schema`，而 `V8__remove_projects.sql` 实际上变为描述 `remove_projects`（前导空格）。建议统一成 `V8__remove_projects.sql` → 维持原样（不阻塞，但记录在案）。

### P2 — 公共 API 无速率限制

所有 `GET /api/v1/posts`、`/api/v1/search` 等端点无防刷机制。恶意请求可轻易消耗数据库连接。

---

## 三、功能拓展方案

### F1 — 评论系统

**复杂度**：⭐⭐⭐⭐（5 个工作日）
**用户价值**：⭐⭐⭐⭐⭐

#### 技术设计

**数据模型**（新建表 `comments`）：

```sql
CREATE TABLE comments (
    id              BIGSERIAL PRIMARY KEY,
    post_id         BIGINT REFERENCES posts(id) ON DELETE CASCADE,
    parent_id       BIGINT REFERENCES comments(id) ON DELETE CASCADE,  -- 嵌套回复
    author_name     VARCHAR(100) NOT NULL,
    author_email    VARCHAR(254),                                      -- 可选，仅用于 Gravatar
    content         TEXT NOT NULL CHECK (char_length(content) <= 2000),
    is_approved     BOOLEAN NOT NULL DEFAULT false,                    -- 管理员审核
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_comments_post ON comments(post_id, created_at);
CREATE INDEX idx_comments_parent ON comments(parent_id);
```

**后端**（4 个新文件）：
- `CommentEntity.java` — JPA 实体
- `CommentRepository.java` — 分页查询 + 按 post_id 查询
- `CommentService.java` — 创建、审核、删除
- `CommentController.java` — `GET /api/v1/posts/{slug}/comments`（公开，无需登录）、`POST /api/v1/posts/{slug}/comments`（创建，需验证码或速率限制）、`DELETE /api/v1/admin/comments/{id}`（管理）

**前端**（1 个新组件）：
- `components/CommentSection.vue` — 嵌套评论树、回复表单、Markdown 支持、Gravatar 头像

**安全措施**：
- 每条评论不超过 2000 字符
- 创建间隔 ≥ 30 秒（速率限制，内存 `ConcurrentHashMap<IP, Instant>`）
- 新评论默认 `is_approved = false`，管理员审核后可见
- 使用 Jsoup 清洗 HTML（复用 `PostContentSanitizer` 策略）

#### 测试
- `CommentServiceTest`（6 tests）
- 现有 `BlogApiIntegrationTest` 增加一个 `@Order(9)` 测试评论创建与审核流程

---

### F2 — 全文搜索升级

**复杂度**：⭐⭐⭐（2 个工作日）
**用户价值**：⭐⭐⭐⭐⭐

#### 现状

```java
// SearchService.java: 三张表分别 LIKE %keyword%，大小写不敏感但性能差
var likePattern = "%" + normalized + "%";
postRepository.searchPublished(likePattern, pageable);
```

不支持中文分词、不支持相关性排序、不支持高亮。

#### 技术设计

**方案 A：PostgreSQL tsvector（推荐）**

在表中添加 `tsvector` 列，使用触发器同步更新：

```sql
ALTER TABLE posts ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (
    setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('simple', coalesce(excerpt, '')), 'B') ||
    setweight(to_tsvector('simple', coalesce(category, '')), 'C') ||
    setweight(to_tsvector('simple', coalesce(content, '')), 'D')
  ) STORED;
CREATE INDEX idx_posts_search ON posts USING GIN(search_vector);
```

查询变为：
```sql
SELECT * FROM posts WHERE search_vector @@ plainto_tsquery('simple', :query)
ORDER BY ts_rank(search_vector, plainto_tsquery('simple', :query)) DESC;
```

为 dish 和 note 表做同样处理。

**迁移文件**：`V9__add_fulltext_search.sql`

**后端变更**：
- `PostRepository.java` — 新增原生 SQL 查询方法 `searchPublishedVector()`
- `DishRepository.java` — 同上
- `NoteRepository.java` — 同上
- `SearchService.java` — 优先使用向量搜索，回退到旧的 LIKE 方式

**前端变更**：
- `GlobalSearch.vue` — 添加结果高亮（用 `<mark>` 包裹匹配词）
- `useSearch.ts` — 传递额外的高亮字段

**方案 B：pg_trgm 扩展**

如果不需要中文分词，`pg_trgm` 扩展提供 `similarity()` 函数，支持模糊匹配。

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_posts_title_trgm ON posts USING GIN(title gin_trgm_ops);
SELECT * FROM posts WHERE title % :query ORDER BY similarity(title, :query) DESC;
```

#### 测试
- `SearchServiceTest` 扩充 3 个 tests
- `BlogApiIntegrationTest` 增加全文搜索校验

---

### F3 — RSS/Atom 订阅

**复杂度**：⭐（0.5 个工作日）
**用户价值**：⭐⭐⭐

#### 技术设计

```java
// 使用 Rome 库或手动构建 XML
@GetMapping(value = "/feed.xml", produces = MediaType.APPLICATION_ATOM_XML_VALUE)
public String feed() { /* 构建 RSS 2.0 XML */ }
```

**实现**：
- 不需要新依赖（手动 XML 拼接，或添加 `rome` / `spring-boot-starter-webflux`）
- 路由：`GET /feed.xml` — 最近 20 篇已发布文章 + 菜品 + 笔记
- `<item>` 包含：title, link, description, pubDate, category
- 在 `index.html` 添加 `<link rel="alternate" type="application/atom+xml" href="/feed.xml">`

#### 测试
- `MockMvc` 测试：请求 `/feed.xml`，验证返回 XML 结构正确

---

### F4 — Sitemap 自动生成

**复杂度**：⭐（0.5 个工作日）
**用户价值**：⭐⭐⭐⭐

#### 技术设计

```java
@GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
public String sitemap() {
    var urls = new ArrayList<String>();
    urls.add(buildUrl("/", "daily", 1.0));
    urls.add(buildUrl("/articles", "daily", 0.9));
    // + 所有公开文章、菜品、笔记的 URL
    return buildSitemapXml(urls);
}
```

**实现**：
- 新建 `SitemapController.java`
- 缓存结果（文章不频繁变动，每小时刷新一次）
- `index.html` 添加 `<link rel="sitemap" type="application/xml" href="/sitemap.xml">`

---

### F5 — 管理仪表盘增强

**复杂度**：⭐⭐（1 个工作日）
**用户价值**：⭐⭐⭐

#### 当前
`AdminDashboardController` 返回 `{posts, dishes, notes}` 三个数字。

#### 扩展

在 `/api/v1/admin/stats` 添加更多字段：

```java
public record Stats(
    long posts, long dishes, long notes,
    long publishedPosts, long draftPosts,     // 按状态细分
    long publishedNotes, long archivedNotes,
    long publishedDishes,
    long totalAttachments,                     // 附件存储量
    long storageBytes,                         // 总存储字节
    List<RecentItem> recentActivity            // 最近 5 条变更
) {}
```

**前端变化**：
- `AdminDashboard.vue` 的 3 个 stat card 扩充为 6-8 个
- 添加时间线组件展示最近活动
- 添加简单图表（纯 CSS 环形进度条，不引入 chart 库）

---

### F6 — 菜品份量缩放

**复杂度**：⭐（0.5 个工作日）
**用户价值**：⭐⭐⭐⭐

#### 技术设计

**后端**（无变化）：`DishEntity` 已有 `ingredients`（`List<String>`），格式为 `"嫩豆腐 400 克"`。不需要改数据库。

**前端**（`FoodSection.vue` 新增逻辑）：
```typescript
const servings = ref(2)  // 当前份数
const originalServings = ref(4)  // 原始份数

function scaledAmount(ingredient: string): string {
  // 提取数字部分，按 servings/originalServings 缩放
  return ingredient.replace(/([\d.]+)\s*(克|毫升|ml|g)/g, (_, num, unit) => {
    return `${(parseFloat(num) * servings.value / originalServings.value).toFixed(0)}${unit}`
  })
}
```

**交互**：
- 在菜品详情头部的"准备时间"旁边增加份数选择器（`-/2/3/4/+`）
- 点击切换时食材列表实时缩放

---

### F7 — 阅读统计

**复杂度**：⭐⭐（1.5 个工作日）
**用户价值**：⭐⭐⭐

#### 技术设计

**后端**：
- 新增 `PageView` 实体（id, postId/noteId/dishId, type, viewedAt, ipHash）
- 新增 `PageViewRepository` — `countByPostIdAndViewedAtBetween()`
- 拦截器或过滤器：对 `GET /api/v1/posts/{slug}` 等公共内容接口自动计数（对同一 IP 5 分钟去重）
- `AdminDashboardController` 扩充：返回热门文章 TOP 5

**隐私考虑**：
- 不存真实 IP，仅存 `SHA256(IP + salt)[:16]`
- 不存 user-agent
- 数据保留 90 天，定时清理

---

### F8 — 后台笔记协作优化

**复杂度**：⭐⭐（1 个工作日）
**用户价值**：⭐⭐⭐⭐

#### 当前问题
- 笔记编辑器中，保存后需要等待响应才能继续编辑
- 多标签页编辑同一笔记时，乐观锁触发版本冲突

#### 改进

1. **即时保存（乐观 UI）**：用户输入后立刻在本地标记保存进度，后台异步同步。当版本冲突时弹出提示，不丢数据。
2. **标签页未保存提示**：离开未保存的标签页时，Tab 上显示红点
3. **快捷键面板**：`Ctrl+/` 弹出快捷键参考（已部分存在，但未统一）

**前端变更**：`NotesWorkspace.vue` + `TyporaEditor.vue`

---

## 四、非功能优化

### O1 — 前端构建产物优化

| 优化项 | 当前 | 目标 | 措施 |
|--------|------|------|------|
| KaTeX 打包 | 744 KB（独立 chunk） | 按需加载 | 动态 import `katex`，仅在用到公式的页面加载 |
| Tiptap 体积 | ~200 KB（含 ProseMirror） | — | 只在前台管理页面引入，公开页面按需加载（已部分实现） |
| CSS 体积 | 96 KB（单个文件） | 拆分为 3-4 个 lazy CSS | Vite CSS code splitting |

### O2 — 数据库连接池调优

当前使用 HikariCP 默认配置（maximum-pool-size: 10）。建议：
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 5         # 博客类应用，5 连接足够
      minimum-idle: 2
      idle-timeout: 300000
      max-lifetime: 600000
```

### O3 — HTTP 缓存策略

公开内容接口（post/dish/note）可以添加 `Cache-Control` 头：

```java
// 在 Controller 或 Filter 中添加
Cache-Control: public, max-age=300, s-maxage=600
```

对 `note-assets/{publicId}` 添加 `Cache-Control: private, no-store`（已实现）。

### O4 — 前端首屏性能

| 措施 | 说明 |
|------|------|
| 预加载英雄区关键 CSS | `<link rel="preload" href="/styles.css" as="style">` |
| 字体预加载 | 使用 `font-display: swap` |
| 图片懒加载 | 已部分实现（IntersectionObserver），统一使用 `loading="lazy"` |
| 预连接 API 端点 | `<link rel="preconnect" href="http://localhost:8080">` |

---

## 五、推荐路线

### Sprint 6 — 评论系统（5 天）
- Comment Entity + Repository + Service + Controller
- CommentSection.vue 前端组件
- 速率限制、审核机制
- 集成测试

### Sprint 7 — 全文搜索升级（2 天）
- V9 Flyway 迁移（tsvector 列 + GIN 索引）
- Repository 新增向量查询方法
- SearchService 双模式（向量优先，LIKE 回退）
- 前端高亮

### Sprint 8 — SEO + 性能（1 天）
- RSS/Atom feed
- Sitemap 自动生成
- HTTP 缓存头
- 数据库连接池调优

### Sprint 9 — 仪表盘 + 菜品体验（1 天）
- 管理仪表盘统计增强
- 菜品份量缩放

### Sprint 10 — 阅读统计 + 协作优化（2.5 天）
- PageView 埋点与统计
- 热门内容排名
- 笔记乐观保存

### Sprint 11 — 构建产物 + 首屏（1 天）
- KaTeX 动态导入
- 关键 CSS 预加载
- 安全加固（CORS 收紧、类型安全清理）

---

## 六、各 Sprint 工时估算

| Sprint | 前端 | 后端 | 测试 | 总计 |
|--------|------|------|------|------|
| 6 — 评论系统 | 1.5d | 2d | 1.5d | 5d |
| 7 — 全文搜索 | 0.5d | 1d | 0.5d | 2d |
| 8 — SEO + 性能 | 0d | 1d | 0d | 1d |
| 9 — 仪表盘 + 菜品 | 1d | 0d | 0d | 1d |
| 10 — 阅读统计 | 0.5d | 1.5d | 0.5d | 2.5d |
| 11 — 构建 + 安全 | 0.5d | 0.5d | 0d | 1d |
| **合计** | **4d** | **6d** | **2.5d** | **12.5d** |

---

## 七、风险与决策记录

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| PostgreSQL tsvector 中文分词不支持 | 中 | 高 | 换用 `pg_trgm` 或 SCWS 插件；或 Segsie |
| 评论系统引入安全风险 | 低 | 高 | 审核机制 + Jsoup 清洗 + 速率限制 |
| PWA Service Worker 缓存策略导致更新不及时 | 低 | 中 | 使用 `autoUpdate` registerType（已配置） |
| Tiptap 与 Vue 版本兼容性 | 低 | 中 | 锁定版本依赖，CI 中运行 typecheck |
| 图片压缩影响上传性能 | 低 | 低 | 仅对 JPEG 做压缩，控制在 200ms 以内 |

---

## 八、附录：代码库关键指标

| 指标 | 当前值 | 目标 |
|------|--------|------|
| 前端测试数 | 34 | 40+ |
| 后端测试数 | 52 | 70+ |
| 前端包体积（gzip） | 462 KB | < 400 KB |
| 后端启动时间 | ~7s | < 5s |
| 前端 TypeScript 错误 | 0 | 0 |
| CSS 自定义属性覆盖率 | 100% | 100% |
| PWA Lighthouse 分数（预估） | ~70 | 90+ |
