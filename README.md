# 余白 · 前后端分离博客

一个由 Vue 3 + TypeScript 前端与 Spring Boot + PostgreSQL 后端组成的个人博客。前后端拥有独立的依赖、构建和运行流程，通过 REST API 通信。

## 项目结构

```text
BlogDemo/
├── frontend/   Vue 3、TypeScript、Vite、Pinia、Axios
├── backend/    Java 21、Spring Boot、Spring Data JPA、Flyway
├── storage/    后续用于本地笔记与附件（不提交）
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

首批只读接口：

- `GET /api/v1/posts`
- `GET /api/v1/posts/{slug}`
- `GET /api/v1/categories`
- `GET /api/v1/projects`
- `GET /api/v1/notes`（仅返回公开学习笔记）

管理接口：

- `POST /api/v1/auth/login`
- `GET|POST /api/v1/admin/posts`
- `PUT|DELETE /api/v1/admin/posts/{id}`
- `GET|POST /api/v1/admin/projects`
- `PUT|DELETE /api/v1/admin/projects/{id}`
- `GET|POST /api/v1/admin/notes`：笔记列表与新建
- `GET|PUT|DELETE /api/v1/admin/notes/{id}`：笔记读取、自动保存与删除
- `POST /api/v1/admin/notes/import`：上传 `.md`、`.markdown` 或 `.txt`（最大 2 MB）
- `GET /api/v1/admin/notes/{id}/export`：导出 UTF-8 Markdown

除登录外，所有管理接口都必须携带有效的管理员 Bearer Token。

## 启动前端

另开一个 PowerShell：

```powershell
cd frontend
npm install
npm run dev
```

前端默认地址为 `http://localhost:5173`，开发代理会把 `/api` 请求转发到 Spring Boot。后端暂未启动时，页面会使用内置内容作为安全回退，不影响现有公开站点。

后台入口为 `http://localhost:5173/admin/login`。登录令牌仅保存在当前浏览器会话中，关闭该会话后需要重新登录。

登录后从内容工作台进入 `http://localhost:5173/admin/notes`。学习笔记工作室支持所见即所得与 Markdown 源码双模式、标题大纲、任务清单、表格、代码块、标签/目录/状态管理、本地 Markdown 导入、导出和 1 秒防抖自动保存。`Ctrl/Cmd + S` 可立即保存，`Ctrl/Cmd + Shift + F` 切换专注模式。

## 构建

```powershell
cd backend
mvn clean package

cd ..\frontend
npm run build
```
