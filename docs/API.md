# API 约定

用户确认的 15 项扩展业务详见 [业务 API](BUSINESS-API.md)；以下为原有基础接口，入住、移出和归档同样受新增床位、房间状态和交接规则约束。

所有接口以 `/api` 开头，返回 JSON；导出和验证码、附件返回二进制。错误格式为 `{ "success": false, "message": "错误说明" }`，使用 400 / 401 / 403 / 404 / 409 / 413 / 429 / 500 等 HTTP 状态码。

认证使用会话 Cookie。先 GET `/auth/csrf` 获得 `token`、`headerName`，所有 POST / PUT / PATCH / DELETE 请求在该 header 中携带 token。登录成功后旧 CSRF token 失效，需再次 GET `/auth/csrf`。前端请求携带 `credentials: include`。退出使用 POST。

| 接口 | 方法 | 权限 / 说明 |
| --- | --- | --- |
| `/auth/csrf` | GET | 公开，获取 CSRF token |
| `/auth/captcha` | GET | 公开，验证码 JPEG，有效 2 分钟，校验后作废 |
| `/auth/login` | POST | 公开，username/password/captcha；按来源与账号限流 |
| `/auth/register` | POST | 已关闭公开自助注册，新账号返回 403；学生由管理员核验后开通 |
| `/auth/me` | GET | 登录用户，返回 user，不含密码 |
| `/auth/metrics` | GET | 仅管理员，认证聚合计数和有界近期事件 |
| `/auth/logout` | POST | 登录用户，注销会话 |
| `/students` | GET | 管理员，分页筛选 |
| `/students` | POST | 管理员，新增学生，可用 initialPassword 创建对应账号 |
| `/students/{id}` | GET / PUT / DELETE | 管理员，学号不可修改；DELETE 现在办理毕业归档，不硬删除学生与历史 |
| `/students/batch-delete` | POST | 管理员批量归档，`{ids:[1,2]}`，最多 100 条，整批事务，须先退宿结清交接 |
| `/dormitories` | GET / POST | 管理员，列表 / 新增 |
| `/dormitories/{id}` | GET / PUT / DELETE | 管理员，详情含 students；删除要求无入住 |
| `/dormitories/batch-delete` | POST | 管理员，`{ids:[1,2]}` |
| `/dormitories/floors?building=1号楼` | GET | 管理员，楼层列表，building 默认 all |
| `/dormitories/{id}/students/{studentId}` | POST / DELETE | 管理员，入住 / 移出，studentId 是学生记录主键 |
| `/buildings` | GET / POST | 管理员，列表 / 新增并生成宿舍 |
| `/buildings/{id}` | GET / PUT / DELETE | 管理员，编辑保留房间 ID 和入住关系 |
| `/data/statistics`、`/data/refresh` | GET | 管理员，统计数据；不提供重置接口 |
| `/export/students`、`/export/dormitories`、`/export/buildings`、`/export/all` | GET | 管理员，XLSX 下载 |
| `/me` | GET | 学生，仅本人档案及宿舍 |
| `/me/profile` | PUT | 学生，只接受 name/grade/className/gender/phone，学号取当前账号 |
| `/repairs?status=待处理` | GET | 学生本人及本寝室公共工单；管理员全部；宿管负责楼栋；维修人员仅已派给自己的工单 |
| `/repairs` | POST | 按业务规则允许全部已入住学生或仅宿舍长，multipart：repairType/description/image（可选） |
| `/repairs/{id}/image` | GET | 记录所有者、管理员、维修人员 |
| `/repairs/{id}/status` | PATCH | 兼容开始处理入口，仍检查指派和顺序；完整派单、完成说明与学生验收见扩展业务 API |
| `/ai/chat` | POST | 管理员，`{"message":"统计摘要"}`；每账号每分钟10次；返回结果、来源、时间和统计依据 |
| `/ai/status` | GET | 管理员，返回是否配置 DeepSeek 及模型名，不返回密钥 |
| `/ai/statistics`、`/ai/summary` | GET | 管理员 |

AI 查询类型、数据流和降级规则见 [统计助手](AI-ASSISTANT.md)。

学生列表筛选：studentId、name、grade、className、gender、dormitoryNumber、sortBy、sortOrder、page、size。宿舍列表：dormitoryNumber、building、roomType、gender、occupancy（all/empty/not_full/full）、floor、sortBy、sortOrder、page、size。默认每页 10 条，最多 100 条；页码从 1 开始。排序字段白名单，SQL 值参数化。

学生创建/编辑结构：`{name,studentId,grade,className,gender,phone,dormitoryNumber,initialPassword?}`。未分配宿舍可传空字符串。初始密码至少 8 字符且 UTF-8 不超过 72 字节；已有账号可省略，省略不会覆盖密码。

宿舍创建/编辑结构：`{dormitoryNumber,building,roomType,gender,remark}`，人数由后端计算，容量从二/四/六人间自动确定，禁止客户端指定人数。宿舍号如 `1号楼-0101`。

楼栋结构：`{name:"1号楼",gender:"男",sortMode:"asc",floors:[{doubleRooms:0,fourRooms:5,sixRooms:5}]}`。最多 30 层、每层最多 99 间。更改已入住房间时校验容量和性别，删除有入住的房间或楼栋会返回 409，整次编辑回滚。
