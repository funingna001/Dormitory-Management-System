# 数据库与附件备份恢复

`scripts/backup-restore.py` 同时保存全部业务表与 `UPLOAD_DIR` 指向的附件目录。备份不包含 `.env` 或数据库密码；备份中的账号哈希、学生信息、业务记录和图片仍须作为敏感数据保管。

## 创建与校验

先停止所有后端实例、定时任务和其他数据库／附件写入程序，再执行备份。`--offline` 表示操作者已完成停写；脚本不会自动停止正在运行的服务。MySQL 使用一致性事务快照，附件在同一次停写窗口复制，成功后再启动服务。

```powershell
python scripts/backup-restore.py create --engine mysql --offline
python scripts/backup-restore.py verify --backup backups/full/full-实际时间-随机编号
```

默认从进程环境变量和项目 `.env` 读取 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`UPLOAD_DIR`。环境变量优先。无 `DB_URL` 时使用 `127.0.0.1:3306/susheguanli`；附件默认 `uploads/repair`。可显式指定 `--host`、`--port`、`--database`、`--user`、`--password-env`、`--upload-dir`、`--output`。相对路径以当前工作目录为准，因此示例均从项目根目录执行。

MySQL 模式需要 PyMySQL，可在独立虚拟环境中安装：`python -m pip install pymysql`。数据库表须为 InnoDB；当前格式针对本项目的普通表，不支持视图、触发器、存储过程、事件和数据库账号授权。这些对象应由数据库原生工具另行管理。备份不会设置全局锁，也不需要读取 MySQL 系统账号表。

SQLite 模式显式指定实际数据库文件，不能用旧 SQLite 副本替代当前 MySQL 数据：

```powershell
python scripts/backup-restore.py create --engine sqlite --sqlite-path path/to/current.db --offline
```

每个完整备份目录包含 `manifest.json`、数据库快照或逐表结构／数据文件、`uploads/`。清单记录全部表的行数与内容指纹，以及每个文件的 SHA-256。创建完成后自动校验，文件缺失、损坏或不支持的数据类型会使操作失败。失败目录不会标记为完整，也不会触发保留周期清理。清单校验用于发现意外损坏，不是防篡改签名。

## 保留周期

默认保留最近 **30 天**，同时至少保留最新 **7 份**完整备份。每次新备份成功并校验后，只清理同一输出目录下由该脚本生成、符合命名规则且带完整清单的过期目录；手工备份和其他文件不参与清理。

```powershell
python scripts/backup-restore.py create --engine mysql --offline --keep-days 60 --keep-last 14
```

定时备份需要自行配置任务计划，将停写、备份和启动服务安排在同一流程中。备份还应复制到独立存储，并定期恢复检查。

## 恢复演练

先由数据库管理员创建一个**全新的空数据库**并为恢复账号授权。恢复命令要求显式指定与来源不同的数据库名，以及不存在的附件目录，拒绝覆盖来源库、已有表或已有附件目录：

```powershell
python scripts/backup-restore.py restore --backup backups/full/full-实际时间-随机编号 --database susheguanli_restore_check --upload-dir artifacts/restore-check-images
```

SQLite 恢复到不存在的新文件：

```powershell
python scripts/backup-restore.py restore --backup backups/full/full-实际时间-随机编号 --sqlite-path artifacts/restore-check.db --upload-dir artifacts/restore-check-images
```

恢复前校验全部备份文件；恢复后逐表核对行数和内容指纹，并核对附件哈希。MySQL 的表结构包含原有索引。DDL 无法整体事务回滚；中途失败可能在新目标库留下不完整的表，附件复制失败也可能留下不完整目录。保留错误现场，另选新的空目标重试，禁止把失败目标接入应用。

恢复后使用单独端口启动应用，指向恢复库和恢复附件目录，检查登录、住宿历史、账单、维修图片与检查图片。确认恢复结果后再安排正式切换。

## 自动化回归

```powershell
python scripts/test-backup-restore.py
# 如有专门启动的本机临时 MySQL 实例，还可执行 MySQL 完整恢复验证：
$env:TEST_BACKUP_MYSQL_PORT = '临时实例端口'
python scripts/test-backup-restore.py
```

MySQL 演练使用临时实例的 root 账号，密码由 `TEST_BACKUP_MYSQL_PASSWORD` 传入（默认空，适用于隔离临时实例）。仅创建并清理随机命名的 `susheguanli_test_backup_*` 测试库；不连接 `.env` 中的业务库。SQLite 演练使用独立临时文件及模拟附件。

`backup-before-features.py` 是旧版升级时使用的脚本，日常备份使用 `backup-restore.py`。
