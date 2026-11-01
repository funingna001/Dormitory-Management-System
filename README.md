# 宿舍管理系统

这是一个宿舍管理系统的个人练习仓库。

Vue 3 + Vite 前端，Spring Boot 4 + Spring Security 后端，Java 17，支持 MySQL / SQLite。

## 功能

学生档案、楼栋宿舍、床位分配、住宿审批与历史、报修、通知、访客、卫生检查、费用台账、物品交接和 Excel 导入导出。支持管理员、宿管、辅导员、学生和维修人员角色。

管理员概览页提供 DeepSeek 统计查询：识别自然语言问题，在本地查询学生、年级、楼栋入住、房间容量和报修统计。无 Key 时可使用本地统计。

管理员登录后进入功能首页，可按“数据与查询、宿舍与床位、学生与入住、校园服务、费用与物品、系统与权限”六类查找模块，也可搜索“派单”“调宿”“费用”“密码重置”“AI”等关键词直接进入目标模块。左侧按分类导航，模块页面顶部可返回功能首页。

## 环境

JDK 17、Maven 3.9+、Node.js 22.12+。默认 MySQL 8，SQLite 模式无需安装数据库。

已附官方 Maven Wrapper，未安装 Maven 时可用 `./mvnw`（Windows 为 `.\mvnw.cmd`）替换下文 `mvn`。首次使用需要联网下载 Maven 3.9.9。

## 快速体验（独立 SQLite 演示库）

首次启动前可按 [演示说明](docs/DEMO.md) 创建虚构数据，也可直接启动空库。在项目根目录打开 PowerShell：

```powershell
$env:ADMIN_INITIAL_PASSWORD = '请替换为至少12字符的独立强密码'
$env:SQLITE_DB_URL = 'jdbc:sqlite:./demo.db?busy_timeout=10000&foreign_keys=on'
powershell -ExecutionPolicy Bypass -File scripts/start-backend.ps1 -Database sqlite
```

另开终端：

```powershell
cd frontend
npm ci --ignore-scripts
npm run dev
```

打开 http://127.0.0.1:5173 ，输入 `admin`、自行设置的密码和验证码。后端端口为 8030。首次启动自动建表，已有管理员时不覆盖密码。
Linux/macOS 设置相同环境变量后执行 `mvn spring-boot:run -Dspring-boot.run.profiles=sqlite`。

## MySQL 部署

用数据库管理员账号执行，替换占位密码：

```sql
CREATE DATABASE susheguanli CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'dormitory_app'@'127.0.0.1' IDENTIFIED BY 'replace-with-a-unique-password';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, INDEX ON susheguanli.* TO 'dormitory_app'@'127.0.0.1';
```

在根目录执行 `Copy-Item .env.example .env`，填写数据库凭据和 `ADMIN_INITIAL_PASSWORD`（至少12字符、至多72个 UTF-8 字节），然后执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start-backend.ps1
powershell -ExecutionPolicy Bypass -File scripts/start-frontend.ps1
```

默认地址为 `127.0.0.1:3306/susheguanli`，可通过 `DB_URL` 修改。应用自动建表，无需手动导入根目录 `schema.sql`。

## DeepSeek 查询

在 `.env` 设置 `DEEPSEEK_API_KEY` 并重启后端。默认模型 `deepseek-flash`，可用 `DEEPSEEK_MODEL` 修改。
入口：管理员 → 住宿数据概览 → 管理员统计助手。
DeepSeek 仅接收本次提问和固定查询类型说明；数据库统计内容保留在本地，不执行模型生成的 SQL。
服务异常时返回本地统计并提示。配置、接口及口径见 [AI 助手](docs/AI-ASSISTANT.md)。

## 构建与验证

```powershell
mvn test package
cd frontend
npm ci --ignore-scripts
npm run build
```

后端测试使用独立临时数据库，AI 测试使用本机模拟服务，无需真实 API Key。GitHub Actions 自动运行后端验证与前端构建。
生产环境运行 `java -jar target/dormitory-1.0.0.jar`，前端部署 `frontend/dist/`。参考 [Nginx 配置](deploy/nginx.conf) 统一域名；HTTPS 部署设置 `COOKIE_SECURE=true`。

## 目录与文档

| 路径 | 内容 |
| --- | --- |
| `frontend/` | Vue 前端 |
| `src/main/java/` | 认证、业务与 REST API |
| `src/main/resources/db/` | MySQL / SQLite 建表脚本 |
| `src/test/` | 认证、权限、业务、并发与 AI 测试 |
| `docs/API.md` | 接口与角色权限 |
| `docs/BUSINESS-FEATURES.md` | 业务说明 |
| `docs/REPAIR-WORKFLOW.md` | 报修、派单、维修与验收流程 |
| `docs/BACKUP-RESTORE.md` | 备份恢复 |

发布见 [发布检查](docs/RELEASING.md)，开发见 [CONTRIBUTING](CONTRIBUTING.md)，安全反馈见 [SECURITY](SECURITY.md)。
`legacy/` 为不参与构建的旧代码；费用为台账，不含真实支付、短信或门禁硬件接入。
采用 [MIT License](LICENSE)。
