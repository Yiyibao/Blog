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

## 启动前端

另开一个 PowerShell：

```powershell
cd frontend
npm install
npm run dev
```

前端默认地址为 `http://localhost:5173`，开发代理会把 `/api` 请求转发到 Spring Boot。后端暂未启动时，页面会使用内置内容作为安全回退，不影响现有公开站点。

## 构建

```powershell
cd backend
mvn clean package

cd ..\frontend
npm run build
```
