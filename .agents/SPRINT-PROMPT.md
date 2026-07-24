# Sprint Execution Prompt

将以下指令注入你的系统提示，然后按顺序执行每个 Sprint。

---

## 项目基线

- 仓库：`D:\Office\Study\code\BlogDemo`
- 分支：`main`
- 基线提交：`0b0bc5303f7479c20e04fd19977b331e655a1c85`（不创建提交，不改动远端的基线）
- 前端：Vue 3 + TypeScript + Vite，`frontend/`
- 后端：Spring Boot 3.5.16 + Java 21 + PostgreSQL，`backend/`
- CI：`.github/workflows/ci.yml`

## 通用约束

1. **不创建 git commit/push**，除非明确要求。
2. 不修改 `.env.properties`，不提交真实凭据。
3. `README.md~` 不可提交或删除（`.gitignore` 已有 `*~` 规则）。
4. 不改动业务行为，不弱化现有断言。
5. 每次修改后，如涉及前端代码，必须验证：
   - `npm test`（frontend）通过
   - `npm run test:typecheck`（frontend）通过
   - `npm run build`（frontend）通过
6. 如涉及后端代码，必须验证：
   - `mvn --batch-mode test`（backend）通过
7. `git diff --check` 无空格/冲突标记错误。
8. 不新增 `skip`、`only`、`todo` 测试。
9. 不删除现有测试。
10. 只改动 Sprint 计划中列出的文件。不重构计划外的业务代码。

## 代码风格

1. 使用 TypeScript strict 模式。避免 `any`，测试文件可放宽。
2. Vue 组件使用 `<script setup lang="ts">` 组合式 API。
3. 不添加注释（除非必须解释非常规做法）。
4. 组件命名：PascalCase，单词全拼（如 `AdminDashboardPage`）。
5. 文件命名：kebab-case 或 PascalCase，与所在模块约定一致。
6. Pinia store 使用组合式 API（`defineStore('name', () => { ... })`）。
7. 后端代码保持现有包结构和命名风格。
8. 不引入新的 npm 依赖（除非 Sprint 计划明确要求）。
9. 不引入新的 Maven 依赖（除非 Sprint 计划明确要求）。

## Sprint 计划

### Sprint 1 — 路由重构 + Pinia store + ESLint

目标：清理前端技术债。

#### 文件列表

```
frontend/src/router/index.ts           # 新建 — 路由配置
frontend/src/pages/HomePage.vue         # 新建 — 首页
frontend/src/pages/ArticlesPage.vue     # 新建 — 文章归档
frontend/src/pages/ArticlePage.vue      # 新建 — 文章详情
frontend/src/pages/AboutPage.vue        # 新建 — 关于
frontend/src/pages/NotesPage.vue        # 新建 — 公开笔记（包装 PublicNotes）
frontend/src/pages/RecipesPage.vue      # 新建 — 食谱（包装 FoodSection）
frontend/src/pages/AdminLoginPage.vue   # 新建 — 登录（包装 AdminLogin）
frontend/src/pages/AdminDashboardPage.vue # 新建 — 管理后台（包装 AdminDashboard）
frontend/src/pages/AdminNotesPage.vue   # 新建 — 笔记工作台（包装 NotesWorkspace）
frontend/src/pages/NotFoundPage.vue     # 新建 — 404
frontend/src/stores/auth.ts             # 新建 — Auth store
frontend/src/stores/contentStore.ts     # 新建 — 内容状态 store
frontend/src/stores/uiStore.ts          # 新建 — UI 状态 store
frontend/src/main.ts                    # 修改 — 导入路由配置
frontend/src/App.vue                    # 修改 — 使用 <router-view>
frontend/src/api/admin.ts               # 修改 — 简化，删除 memorySession
.eslintrc.cjs                           # 新建 — ESLint 配置（可选）
```

#### 执行步骤

**Step 1: 创建路由配置**

创建 `frontend/src/router/index.ts`：
- 从 `main.ts` 提取所有 route 定义
- 所有 page 组件使用 `defineAsyncComponent` 懒加载（AdminLogin 和 NotFound 可直接 import）
- 添加路由守卫 `router.beforeEach`：admin 路由（`admin`、`admin-notes`）需要登录验证，未登录重定向到 `/admin/login`
- 添加 `scrollBehavior`

**Step 2: 创建 Page 组件**

对于包装型 Page（Notes / Recipes / AdminLogin / AdminDashboard / AdminNotes）：
- 导入对应 Component，模板中直接渲染

对于独立 Page（Home / Articles / Article / About）：
- 从 `App.vue` 的对应 `v-if` 分支复制模板
- 在 `<script setup>` 中通过 `useContentStore()` 获取所需状态和方法
- 从 `useUiStore()` 获取 toast 和 theme 方法
- HomePage 需要：`posts`, `featuredPost`, `heroStyle`, `contentError`, `toggleFavorite`, `categories` 等
- ArticlesPage 需要：`posts`, `query`, `category`, `sortOrder`, `archivePage`, `favorites`, 所有过滤/分页计算属性
- ArticlePage 需要：`currentPost`, `articleOutline`, `relatedPosts`, `favorites`, `toggleFavorite`, `copyCurrentLink`
- AboutPage：静态模板，无共享状态

Important：HomePage 和 ArticlesPage 都需要在 onMounted 中调用 `loadRemoteContent()`，但要确保只加载一次。可以在 store 中用 `contentReady` 标记控制。

**Step 3: 重构 App.vue**

- 删除所有 route-specific 模板（`v-if="route.name === 'xxx'"` 分支）
- 保留：site-shell div、reading-progress bar、sakura petals、site-header（含导航和搜索/主题按钮）、mobile-nav、main 中的 `<router-view />`、site-footer、back-to-top button、GlobalSearch、toast
- 保留 script 中的：`menuOpen`, `isAdminRoute`, `onKeydown`, `updateProgress`, `scrollToTop`, `setupReveals`, `handlePointerMove`, `handlePointerOut`, `scheduleProgressUpdate`
- `isAdminRoute` 通过 `route.path.startsWith('/admin')` 判断
- 保留 `onMounted` 中的 theme 初始化、滚动监听
- 保留 `watch(isDark, ...)` 主题同步
- 删除不再使用的计算属性和方法（`categories`, `filteredPosts`, `featuredPost`, `currentPost`, `relatedPosts`, `articleOutline`, `heroStyle`, `loadRemoteContent`, `ensureArticleDetail`, `toggleFavorite`, `toggleTheme`, `showToast`, `openSearch` 等——这些迁移到 stores 中）

**Step 4: 验证**

- `npm test`（21 个测试应全部通过，因为测试不依赖路由重构）
- `npm run test:typecheck`
- `npm run build`
- 手动检查：所有路由可导航，页面视觉一致

### Sprint 2 — 后端单元测试补全 + SearchService 升级

目标：提升后端质量。

#### 文件列表

```
backend/src/test/java/com/yubai/blog/post/PostServiceTest.java    # 新建
backend/src/test/java/com/yubai/blog/dish/DishServiceTest.java    # 新建
backend/src/test/java/com/yubai/blog/note/NoteServiceTest.java    # 新建
backend/src/test/java/com/yubai/blog/search/SearchServiceTest.java# 新建
backend/src/main/java/com/yubai/blog/post/PostRepository.java     # 修改 — search 加 LIMIT
backend/src/main/java/com/yubai/blog/dish/DishRepository.java     # 修改 — search 加 LIMIT
backend/src/main/java/com/yubai/blog/note/NoteRepository.java     # 修改 — search 加 LIMIT
```

#### 执行步骤

**Step 1: 创建 Service 单元测试**

使用 Mockito mock Repository 层，不启动 Spring 上下文：
- `PostServiceTest`：slug 唯一性校验、status 过滤、featured post 排序、create/update/delete
- `DishServiceTest`：published 过滤、displayOrder 排序、create/update 验证
- `NoteServiceTest`：乐观锁冲突（`@Version` 递增）、publish/unpublish/archive 状态机、并发版本控制
- `SearchServiceTest`：空结果、跨 Post/Dish/Note 合并、limit 截断

**Step 2: SearchService 升级**

为 `PostRepository`、`DishRepository`、`NoteRepository` 的 `searchPublished()` JPQL 查询添加 `LIMIT` 参数。

**Step 3: 验证**

- `mvn --batch-mode test`

### Sprint 3 — 深色模式 + PWA

目标：提升读者体验。

#### 文件列表

```
frontend/src/styles.css                        # 修改 — CSS 自定义属性重构
frontend/src/stores/uiStore.ts                 # 修改 — 完善 theme 逻辑
frontend/src/components/GlobalSearch.vue       # 修改 — scoped CSS 适配深色
frontend/vite.config.ts                        # 修改 — PWA 插件
frontend/index.html                            # 修改 — manifest, theme-color
frontend/public/icons/                         # 新建 — PWA 图标
```

#### 执行步骤

**Step 1: CSS 自定义属性重构**

- 在 `styles.css` 顶部定义 `:root` 和 `[data-theme="dark"]` 自定义属性集
- 逐步替换硬编码颜色值

**Step 2: PWA 配置**

- 安装 `vite-plugin-pwa`
- 配置 generateSW 策略

### Sprint 4 — 图片自动优化 + 仪表盘统计

目标：管理端体验。

#### 文件列表

```
backend/pom.xml                                                # 修改 — thumbnailator 依赖
backend/src/main/java/com/yubai/blog/note/NoteAttachmentService.java # 修改 — 图片压缩
backend/src/main/java/com/yubai/blog/admin/AdminDashboardController.java # 新建 — 统计接口
frontend/src/components/AdminDashboard.vue                     # 修改 — 真实统计数据
```

## 执行顺序

1. 先完成 Sprint 1 所有文件，验证通过后通知我
2. 等我确认后，继续 Sprint 2
3. 以此类推

## 异常处理

- 如果 `npm audit` 发现 high/critical 漏洞：分析是否可以小范围升级
- 如果遇到 test 失败：先分析是否是重构引入的，回退后重试
- 如果某个步骤无法在本地完整验证（如 PWA），在完成时说明
