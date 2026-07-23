# 余白 · 前后端分离博客

[![CI](https://github.com/Yiyibao/Blog/actions/workflows/ci.yml/badge.svg)](https://github.com/Yiyibao/Blog/actions/workflows/ci.yml)

一个由 Vue 3 + TypeScript 前端与 Spring Boot + PostgreSQL 后端组成的个人博客。前后端拥有独立的依赖、构建和运行流程，通过 REST API 通信。

## 项目结构

```text
BlogDemo/
├── frontend/   Vue 3、TypeScript、Vite、Pinia、Axios
├── backend/    Java 21、Spring Boot、Spring Data JPA、Flyway
└── docs/       项目文档
```

## 首次配置数据库

在仓库根目录执行：

```powershell
psql -U postgres -f backend/database/bootstrap.sql
```

脚本会提示输入新建数据库用户 `yubai_app` 的密码，并创建 `yubai_blog` 数据库。随后复制本地配置模板：

```powershell
Copy-Item backend/.env.example backend/.env.properties
```

打开 `backend/.env.properties`，把 `DB_PASSWORD` 改成刚才设置的密码。该文件已被 Git 忽略，不会提交真实凭据。

后台管理还需要在同一文件中配置：

```properties
APP_JWT_SECRET=至少32位的随机字符串
APP_ADMIN_USERNAME=你的管理员用户名
APP_ADMIN_PASSWORD=你的强密码
```

首次启动时，后端会用 BCrypt 加密管理员密码并写入数据库。此后不会保存明文密码；JWT 默认两小时过期。

## 启动后端

```powershell
cd backend
mvn spring-boot:run
```

后端默认地址为 `http://localhost:8080`，健康检查为 `http://localhost:8080/actuator/health`。

### 全站搜索

`Ctrl/Cmd + K`（或点击搜索按钮）打开全站搜索面板。输入关键词后 300ms 防抖自动向 `/api/v1/search` 发起请求，结果按文章、美食、学习笔记分组展示，支持键盘上下导航、Enter 跳转和 Escape 关闭。搜索面板使用独立 `AbortController` 取消过期请求，加载/错误/空状态均有对应 UI。

搜索结果使用 `?note={id}` 直接打开指定公开笔记，使用 `?dish={slug}` 直接打开指定菜品。文章归档的本地搜索与全局搜索状态完全独立。

首批只读接口：

- `GET /api/v1/posts?page=0&size=10`：仅返回 `PUBLISHED` 文章，分页字段为 `items/page/size/totalElements/totalPages`
- `GET /api/v1/posts/{slug}`：仅返回已发布文章
- `GET /api/v1/categories`
- `GET /api/v1/dishes?page=0&size=20`：分页返回已发布菜品，并按精选和展示顺序排序
- `GET /api/v1/dishes/{slug}`：读取菜品、食材、步骤和图片署名
- `GET /api/v1/notes?page=0&size=20`、`GET /api/v1/notes/{id}`（仅返回公开学习笔记）
- `GET /api/v1/note-assets/{publicId}`（仅当所属笔记已公开时读取笔记内图片）
- `GET /api/v1/search?q=关键词&limit=5`：按 `articles/notes/dishes` 分组返回公开内容，每组最多 1–10 条

管理接口：

- `POST /api/v1/auth/login`
- `GET /api/v1/admin/posts?page=0&size=20&status=DRAFT|PUBLISHED`：可按状态筛选
- `POST /api/v1/admin/posts`：创建文章，`status` 支持 `DRAFT` / `PUBLISHED`
- `GET|PUT|DELETE /api/v1/admin/posts/{id}`
- `GET /api/v1/admin/dishes?page=0&size=20`、`POST /api/v1/admin/dishes`
- `GET|PUT|DELETE /api/v1/admin/dishes/{id}`
- `GET /api/v1/admin/notes?page=0&size=20&status=DRAFT|PUBLISHED|ARCHIVED`、`POST /api/v1/admin/notes`
- `GET|PUT|DELETE /api/v1/admin/notes/{id}`：笔记读取、自动保存与删除
- `PUT /api/v1/admin/notes/{id}/publish|unpublish|archive`：使用当前版本号发布、恢复草稿或归档笔记
- `POST /api/v1/admin/notes/import`：上传 `.md`、`.markdown` 或 `.txt`（最大 2 MB）
- `GET /api/v1/admin/notes/{id}/export`：导出 UTF-8 Markdown
- `GET|POST /api/v1/admin/notes/{id}/attachments`：列出或上传笔记图片
- `DELETE /api/v1/admin/notes/{id}/attachments/{attachmentId}`：删除笔记图片

除登录外，所有管理接口都必须携带有效的管理员 Bearer Token。
所有内容列表统一返回 `items/page/size/totalElements/totalPages`，页码从 `0` 开始，单页最大 `50` 条。

## 启动前端

另开一个 PowerShell：

```powershell
cd frontend
npm install
npm run dev
```

前端默认地址为 `http://localhost:5173`，开发代理会把 `/api` 请求转发到 Spring Boot。后端暂未启动时，页面会使用内置内容作为安全回退，不影响现有公开站点。

美食模块入口为 `http://localhost:5173/recipes`，页面从 PostgreSQL 菜品 API 读取真实内容，提供分类筛选、Bento 菜谱画廊、评分排行、食材清单、制作步骤和图片来源，并支持键盘关闭详情面板。管理员可在内容工作台的“菜品管理”中增删改查菜品。

后台入口为 `http://localhost:5173/admin/login`。登录令牌仅保存在当前浏览器会话中，关闭该会话后需要重新登录。

登录后从内容工作台进入 `http://localhost:5173/admin/notes`。学习笔记工作室支持所见即所得与 Markdown 源码双模式、标题大纲、任务清单、表格、代码块、KaTeX 公式、多笔记标签页、标签/目录/状态管理、显式发布与撤回、本地 Markdown 导入、导出和 1 秒防抖自动保存。发布前会先保存当前编辑内容，成功后公开页面立即可见。PNG、JPEG、WebP、GIF 图片可直接粘贴、拖入或从工具栏上传，单张最大 8 MB；输入 `/` 可打开快速插入菜单。`Ctrl/Cmd + S` 可立即保存，`Ctrl/Cmd + Shift + M` 切换源码模式，`Ctrl/Cmd + Shift + F` 切换专注模式。

## 构建

```powershell
cd backend
mvn clean package

cd ..\frontend
npm run build
```

## 测试

集成测试使用本地独立库 `yubai_blog_it`（不会改写开发库）。首次执行前：

```powershell
psql -U postgres -c "CREATE DATABASE yubai_blog_it OWNER yubai_app;"
psql -U postgres -d yubai_blog_it -c "ALTER SCHEMA public OWNER TO yubai_app;"
```

然后：

```powershell
cd backend
mvn test
```
