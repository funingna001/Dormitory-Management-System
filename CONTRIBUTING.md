# 参与开发

环境：JDK 17、Maven 3.9+、Node.js 22.12+，默认使用 MySQL 8。

1. 按 README 启动本地环境。使用虚构数据，不提交 `.env`、真实学生资料或附件。
2. 为功能或修复创建独立分支。涉及权限、数据库或外部 API 的改动应包含行为测试。
3. 提交前执行 `mvn test` 和 `cd frontend && npm ci --ignore-scripts && npm run build`（Windows 可分行执行）。
4. PR 中说明问题、变更后的行为和验证结果；页面改动附脱敏截图。

后端测试默认使用独立临时 SQLite 数据库，不需要启动本机 MySQL，也不使用真实 DeepSeek Key。
修改数据结构时同时维护 MySQL/SQLite 两套建表脚本，保留现有数据。
