# 安全修复与验证记录

本次保留现有业务，改造为独立前端和受认证的 JSON API；后续根据用户确认实现全部 15 项业务功能，详见 [业务功能说明](BUSINESS-FEATURES.md)。

## 已修复的项目问题

| 原问题 | 修复 |
| --- | --- |
| 启动时删除学生、宿舍、楼栋并生成随机演示数据 | 移除破坏性初始化；仅非破坏性建表与密码哈希迁移；重复启动保留原数据 |
| 未认证即可调用全量导出、统计、AI 与数据重置 | 集中 Spring Security 规则；管理 API 仅管理员；移除数据重置 API |
| 楼栋和宿舍学生操作只检查登录，普通学生可越权 | 全部管理操作只允许管理员，学生 API 固定使用当前账号 |
| 自报学号可覆盖其他学生档案 | 个人资料请求不接受学号、角色、宿舍或宿舍长属性，身份仅从服务端认证获取 |
| 自助注册与未来学号抢注 | 已关闭公开自助注册；建档和导入拒绝自动复用未绑定账号，学生账号由管理员核验开通 |
| 密码明文、自动生成固定弱密码 | 使用 BCrypt(12)，启动迁移旧密码；不再自动创建演示用户；空库管理员密码来自环境变量；新学生初始密码需明确指定 |
| 管理员与学生绕过验证码、验证码可重用 | 所有角色验证码必填，SecureRandom，2 分钟过期，校验即作废；按账号/来源限流 |
| 会话固定、GET 注销与 GET 删除、CSRF 缺失 | 登录更换 session ID，POST 注销，DELETE 或 POST 执行修改；保留 Spring Security 会话型 CSRF 防护 |
| 账号删除后旧会话仍可访问 | 每次请求确认账号存在且角色一致，否则清除会话 |
| 客户端可直接指定入住人数；入住与床位不同步 | 后端计算人数，校验性别和容量；事务中按固定次序获取写锁；并发入住不会超员 |
| 可用错误宿舍 ID 移出他人 | 同时校验学生与目标宿舍归属 |
| 修改楼栋先删除全部宿舍，造成入住引用丢失 | 按房号保留现有房间 ID；更名同步学生与报修；缩容与删除检查实际入住；整次操作事务回滚 |
| 楼栋名称拼接 SQL | 删除原有直接拼接的破坏性方法；所有外部数据使用占位参数；排序使用字段白名单 |
| 图片上传任意扩展名、静态公开读取 | 只接收有效 PNG/JPEG、限制 5MB 和像素数、重新编码为随机名 PNG；图片通过报修 ID 鉴权读取，取消 uploads 静态映射 |
| 异常将 SQL、路径等细节返回浏览器 | 统一 HTTP 错误响应，详细异常只写服务端日志；用户对象禁止序列化 password |
| AI 密钥写入配置源码 | 删除密钥，改用 DEEPSEEK_API_KEY；未设置时使用本地统计回复；AI API 仅管理员 |
| 数据统计只读取首个楼栋；空分页与 JDBC 数值类型兼容问题 | 修正逐行映射与分页边界，兼容 SQLite/MySQL 数值结果 |
| 报修日期写入后无法读回，异常被伪装为记录不存在 | 统一 Timestamp 写入；读取兼容旧字符串与时间戳；仅无记录异常转为 404 |

Vue 页面使用文本插值，不拼接用户 HTML；生产 Nginx 配置包含 CSP、nosniff、frame-ancestors 等响应头。CORS 只允许明确来源；Cookie 为 HttpOnly、SameSite=Lax，HTTPS 部署需开启 Secure。

报修时间问题使用 [ij-debugger 技能](C:/Users/liangxiaowei/AppData/Local/JetBrains/IntelliJIdea2026.2/aia/agents/views/codex/.agents/skills/ij-debugger/SKILL.md) 实际调试。运行时证据来自 `RepairServiceImpl.java:55`，栈为 `workerMustFollowRepairTransitions:164 → RepairController.status:94 → RepairServiceImpl.findById:55`。读取记录 `id=1` 时，捕获 `UncategorizedSQLException: Error parsing time stamp`，根异常指出 `2026-10-27T22:42:57.129514700` 不匹配 JDBC 驱动要求的日期格式。

- 写入 `LocalDateTime` 被 SQLite 保存为带 `T` 的字符串。
- 驱动的 `getTimestamp` 无法读取该字符串。
- 原代码捕获全部异常并返回 null，控制器将其解释为记录不存在。
- 修复统一时间写入并兼容旧格式，状态流转与新报修读回测试均通过；已移除本次调试断点、停止调试会话。

## 依赖审计

前端执行 `npm audit`，审计锁定依赖。后端从 Maven 实际解析的 runtime 依赖生成坐标列表，使用 `scripts/audit-dependencies.py` 查询 OSV。升级时还核对了官方 Maven Central 元数据与供应商发布说明。

初步审计命中旧 Jackson、Tomcat、Spring MVC、Commons Lang、Log4j API 依赖。因此后端升级为 Spring Boot 4.0.9 / Spring Framework 7，并显式选用修复后的 Tomcat、Commons Lang 和 Log4j 版本；后端 JSON 序列化迁移至 Jackson 3。

最终复扫：前端 `npm audit` 为 0 个已知漏洞；后端 OSV 扫描 81 个实际 runtime 依赖，命中 0 项。扫描仅覆盖公开已收录漏洞，不能证明不存在未知漏洞。审计结果和测试记录保存在 `artifacts/` 与根目录构建日志。

参考：

- [Spring Boot 4 官方迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot 4.0.9 系统要求](https://docs.spring.io/spring-boot/4.0/system-requirements.html)
- [Spring Security CSRF 说明](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Apache POI 发布与更新说明](https://poi.apache.org/changes.html)
- [OSV 漏洞数据库](https://osv.dev/)

## 数据与测试范围

架构改造时的自动化和浏览器验收均使用临时数据库或 `artifacts/smoke.db` 副本。2026-10-28 根据用户授权，已将原数据正式迁入本机 MySQL，并将用户指定的 `2号楼-0101` 调整为六人间、容量 6，保留原 5 名入住学生。MySQL 密码已转换为 BCrypt，原登录密码保持有效。原 SQLite 已与备份逐行比较，5 个用户、432 名学生、150 间宿舍、3 栋楼、5 条报修均未变更。

最终 Spring Boot 4.0.9 构建和打包成功，22 项后端集成测试全部通过。测试覆盖身份认证、真实 CSRF token、角色隔离、越权档案、空分页、排序注入、入住性别与人数、并发入住、整批回滚、宿舍长重算、楼栋改名、重复初始化、账号失效、图片上传、报修流程、导出和本地 AI。

Chrome 浏览器验证管理员登录、列表筛选、入住详情、编辑弹窗、楼栋配置、Excel 下载、报修与移动布局；验证学生保存资料、管理 API 返回 403、宿舍长提交报修、维修人员顺序完成报修。后续已在隔离 MySQL 测试库运行 22 项集成测试，并在正式迁移后的真实 MySQL 服务上验证登录、宿舍详情、学生与报修列表、时间字段、附件、统计、导出和退出；该验收没有增删正式业务数据。外部 AI 服务未配置新密钥，没有真实调用付费服务。

## 15 项业务扩展的最终验证（2026-10-28）

本节为业务扩展后的最新结果，前文 22 项测试属于先前架构迁移阶段。现有 22 项与新增 29 项测试在 SQLite、隔离 MySQL 中各执行一次，均为 51 项通过、零失败。前后端构建成功；Chrome 覆盖五种角色、19 个业务页面、17 个连续业务场景。npm audit 为零命中，OSV 复查 81 个后端运行时依赖为零命中，结果仅代表当前已公开收录的漏洞。

新增安全措施包括：按楼栋 / 辅导员 / 工单派单隔离数据；明确审批状态机；床位并发事务及版本复查；欠费和资产交接校验；缴费并发与重复凭据限制；账号停用及改密撤销会话；初始密码强制修改；导入批次归属、失效、单次确认；XLSX 公式和压缩包展开限制；图片像素及并发限制；JSON 请求体上限。审计不记录请求正文或密码。后端默认仅监听本机，公开部署须显式配置监听地址和 HTTPS 入口。

正式 MySQL 新增 24 张业务表后，共 29 张表。752 个床位、431 个占用床位与开放住宿记录一致，无悬空占用；原有五张表逐行验证通过，原 SQLite 未改动。正式服务登录、新业务只读接口、附件和导出通过；验收未向正式库写入演示业务。临时浏览器测试库和本次验收进程已清理。

记录见 `artifacts/business-production-verification.json`、`artifacts/mysql-live-verification.json`、`artifacts/mysql-http-smoke-result.json`、`artifacts/business-browser-result.json` 及集成测试日志。功能边界与使用步骤见 [业务功能说明](BUSINESS-FEATURES.md)。

## 历史凭据说明

原配置中的 AI 密钥已经暴露，应在供应商控制台撤销并重新生成；删除本地配置不能撤销旧密钥。原数据库的旧弱密码保持原值以避免擅自更改用户凭据，BCrypt 迁移不会让弱密码变强；上线前需更换。备份中保留历史代码及明文密码，不要上传或对外部署 `backups/`、`legacy/`、数据库及 uploads 原始目录。
