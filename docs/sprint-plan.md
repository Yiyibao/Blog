# Sprint 执行计划

## 总览

从当前基线（commit 0b0bc5303f7479c20e04fd19977b331e655a1c85）出发，按顺序完成以下 Sprint。

---

## Sprint 1 — 路由重构 + Pinia store + ESLint

**目标：** 清理前端技术债，为后续功能开发打好架构基础。

### 1.1 路由重构

**现状：** `App.vue` 是唯一组件，所有路由通过 `v-if="route.name === 'xxx'"` 分支渲染。

**改造方案：**
1. 创建 `router/index.ts` 从 `main.ts` 拆分路由配置
2. 为每个 route 创建独立 Page 组件放在 `src/pages/` 下：
   - `HomePage.vue` —— 首页（当前 App.vue 中 `home` 和 `articles` 分支内容）
   - `ArticlePage.vue` —— 文章详情（`article` 分支）
   - `AboutPage.vue` —— 关于（`about` 分支）
   - `NotesPage.vue` —— 公开笔记（`notes` 分支，当前 PublicNotes）
   - `RecipesPage.vue` —— 食谱（`recipes` 分支，当前 FoodSection）
   - `AdminLoginPage.vue` —— 登录（`admin-login` 分支）
   - `AdminDashboardPage.vue` —— 管理后台（`admin` 分支）
   - `AdminNotesPage.vue` —— 笔记工作台（`admin-notes` 分支）
3. 将全局元素（Header、Footer、搜索模态、樱花粒子、回到顶部按钮）提取到 `App.vue` 的 `<slot>` 之外的固定布局
4. 添加路由守卫 `router.beforeEach` 代替 `isAdminRoute` 条件判断
5. 添加 `NotFoundPage.vue` 代替 `/:pathMatch(.*)*` 的简单重定向
6. 使用 `defineAsyncComponent` 进行路由级懒加载

**约束：**
- 不改变页面视觉表现
- 不改变现有路由路径和 name
- 不删除或重写现有组件的内容，只移动位置
- 所有现有测试保持通过（21 个前端测试）

### 1.2 Pinia Store

**现状：** `createPinia()` 已注册但无任何 `defineStore` 调用。状态管理分散在各组件的 `ref` + `sessionStorage`。

**改造方案：**
1. 创建 `src/stores/auth.ts` —— 替代 `src/api/admin.ts` 中的 sessionStorage 管理
   - `token`, `username`, `expiresAt`
   - 初始化时从 `sessionStorage` 恢复
   - login/logout 方法，自动持久化到 `sessionStorage`
   - isAuthenticated / isExpired getter
2. 创建 `src/stores/ui.ts` —— 全局 UI 状态
   - `searchVisible` —— 搜索模态开关
   - 主题相关状态（为 Sprint 4 深色模式预留接口）

**约束：**
- 不破坏 `api/admin.ts` 的 API 调用签名
- `api/admin.ts` 中的 session helper（`clearAdminSession` 等）改为委托给 store
- 保留 `sessionStorage` + in-memory fallback 的兼容逻辑

### 1.3 ESLint

**现状：** 无 lint 规则。

**改造方案：**
1. 在 `frontend/` 下安装 `eslint` + `@eslint/js` + `typescript-eslint` + `eslint-plugin-vue`
2. 创建 `frontend/eslint.config.js`（扁平配置格式）
3. 配置规则：
   - 继承 `@eslint/js` recommended
   - Vue 3 recommended
   - TypeScript strict type-checked（`tsconfig.json` + `tsconfig.vitest.json`）
   - 禁止 `any`（测试文件通过 `overrides` 放宽）
   - 禁止 `console.log`（允许 `console.error`/`console.warn`）
   - import 顺序、未使用变量等
4. 在 `package.json` 添加 `"lint": "eslint ."` 和 `"lint:fix": "eslint --fix ."`
5. 首次运行 `lint:fix` 修复自动可修复问题，手动修复余下问题
6. CI 中不强制 lint 通过（作为参考步骤），留给后续 Sprint

### 验收标准

- [ ] `npm run dev` 正常启动，所有路由可访问
- [ ] 全局 UI（Header、Footer、搜索、樱花、回到顶部）在所有页面一致
- [ ] 管理后台路由无登录时自动跳转 /admin/login
- [ ] `npm test` 21/21 通过
- [ ] `npm run test:typecheck` 通过
- [ ] `npm run build` 通过
- [ ] `npm run lint` 无 error（仅 warning 可接受）
- [ ] `git status --short` 只包含预期文件

---

## Sprint 3 — 后端单元测试补全 + SearchService 升级

**目标：** 提升后端质量门禁，修复搜索体验瓶颈。

### 3.1 后端单元测试

**现状：** 仅 1 个 `ApiResponseTest` 单元测试，9 个集成测试依赖完整 Spring 上下文。

**改造方案：**
1. 为每个 Service 添加单元测试（使用 Mockito mock Repository）：
   - `PostServiceTest` —— create/update 的 slug 唯一性校验、status 过滤逻辑、sanitizer 调用验证
   - `DishServiceTest` —— 发布/未发布过滤、displayOrder 排序
   - `NoteServiceTest` —— 乐观锁冲突、publish/unpublish/archive 状态机转换
   - `SearchServiceTest` —— 空结果、跨表合并、limit 边界
2. 为 `PostContentSanitizer` 添加测试（纯函数，无需 Spring）：
   - XSS payload 过滤
   - 安全标签保留（`<p>`, `<a>`, `<img>` 等）
   - 安全属性保留
3. 为 `PageRequests` 添加参数化测试

**约束：**
- 不改变业务逻辑
- 不修改现有测试
- 测试类放在 `src/test/java/com/yubai/blog/` 对应子包下

### 3.2 SearchService 升级

**现状：** 对三张表分别执行 `LIKE '%keyword%'` 查询，在 Java 层合并结果，不使用 `limit`。

**改造方案：**
1. 为 Post/Dish/Note repository 的 JPQL 查询添加 `LIMIT` 约束
2. 在 PostRepository 和 DishRepository 中增加按关联性排序的查询（标题匹配优先于内容匹配）
3. 考虑使用 PostgreSQL 的 `tsvector` 进行全文检索（可选，评估复杂度后决定是否推迟）

**约束：**
- 搜索 API 签名不变（`GET /api/v1/search?q=&limit=`）
- 返回格式不变（`SearchResponse` 结构）

### 验收标准

- [ ] `mvn test` 9/9 现有 + 新增测试全部通过
- [ ] 新增测试覆盖 Service 层核心分支
- [ ] SearchService 对单表超过 limit 的数据做截断

---

## Sprint 4 — 深色模式 + PWA

**目标：** 提升用户阅读体验，支持离线访问。

### 4.1 深色模式

**现状：** `styles.css` 中有零散的 `.dark-mode` 选择器片段，但未全局统一。

**改造方案：**
1. 在 `styles.css` 顶部定义 CSS 自定义属性（custom properties）：
   ```css
   :root {
     --bg: #ffffff;
     --bg-secondary: #f5f5f5;
     --text: #2c3e50;
     --text-secondary: #666;
     --border: #e0e0e0;
     /* ... 更多 token */
   }
   [data-theme="dark"] {
     --bg: #1a1a2e;
     --bg-secondary: #16213e;
     --text: #e0e0e0;
     --text-secondary: #a0a0a0;
     --border: #2a2a4a;
     /* ... */
   }
   ```
2. 逐步替换 `.class` / `#id` 中的硬编码颜色值
3. `useUIStore` 中添加 `theme` 状态，持久化到 `localStorage`
4. 添加切换按钮（通常在 Header 或侧边栏）
5. 初始主题跟随 `prefers-color-scheme`

### 4.2 PWA

**改造方案：**
1. 安装 `vite-plugin-pwa`
2. 配置 `vite.config.ts`：
   - Service Worker 自动生成（generateSW 策略）
   - 预缓存前端产物
3. 生成 `manifest.json`：
   - name：余白
   - icons：用现有 favicon 或新生成
   - theme_color：从 CSS 变量提取
4. 在 `index.html` 添加 `<link rel="manifest">` 和 `<meta name="theme-color">`

### 验收标准

- [ ] 深色模式切换流畅，无闪烁
- [ ] 深色模式下所有页面可读
- [ ] `localStorage` 保存主题偏好
- [ ] PWA 可安装（桌面/移动端）
- [ ] 离线时显示缓存页面

---

## Sprint 5 — 图片自动优化 + 仪表盘统计

**目标：** 提升管理端体验 + 后端性能。

### 5.1 图片自动优化

**现状：** 笔记附件原样存储（bytea），无压缩/格式转换。菜品图片引用远程/本地路径。

**改造方案：**
1. 添加 `thumbnailator` 依赖（或 `ImageIO`）
2. 在 `NoteAttachmentService.upload()` 中：
   - 对 PNG/JPEG/WebP 图片自动压缩（quality 85%，最大宽度 1920px）
   - 生成 200px 缩略图
3. 添加图片处理配置项（`app.images.max-width`, `app.images.quality`）

### 5.2 仪表盘统计

**现状：** AdminDashboard 是静态面板，无真实数据。

**改造方案：**
1. 后端新增 `AdminDashboardController` 或扩展现有 Controller：
   - `GET /api/v1/admin/stats` —— 返回文章/菜品/笔记总数
   - 可选：近 7 日新增内容统计
2. 前端 `AdminDashboard.vue` 调用并渲染

### 验收标准

- [ ] 上传大图自动压缩至合理大小
- [ ] 缩略图用于列表展示
- [ ] 管理后台首页显示真实数据

---

## 通用约束

- 不要创建或删除提交（`git add` / `git commit` 等），除非明确要求
- 不改动业务行为，不弱化现有断言
- 所有 `npm test`、`npm run test:typecheck`、`npm run build`、`mvn test` 保持通过
- 不要修改 `.env.properties` 或提交真实凭据
- `README.md~` 不可提交或删除
- 每个 Sprint 执行完需要汇报改动文件和验证结果
