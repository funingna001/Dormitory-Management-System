# 业务 API

前缀 `/api/business`。所有接口均要求登录；写操作要求 CSRF。身份、角色、学生归属与楼栋范围由后端获取，客户端不能指定操作人。错误沿用 `{success:false,message}`。

| 路径 | 方法 | 用途与权限 |
| --- | --- | --- |
| `/options` | GET | 当前角色可见的学生、房间、楼栋及维修人员选择项 |
| `/beds` | GET | 床位库存，学生仅看到本寝室或符合性别的可申请空床，其他学生身份会遮蔽 |
| `/rooms/{id}/state` | PATCH | 管理员 / 负责宿管，`state,reason` |
| `/beds/{id}/state` | PATCH | 管理员 / 负责宿管，`state,studentId?,until?,reason` |
| `/housing/move` | POST | 管理员 / 负责宿管，`studentId,bedId?,reason`；无床位表示办理退宿 |
| `/housing/applications` | GET / POST | 授权范围列表 / 学生提交 `kind,bedId?,reason` |
| `/housing/applications/{id}/review` | POST | 管理员 / 负责宿管 / 负责辅导员，`approve,bedId?,note` |
| `/housing/applications/{id}/complete` | POST | 管理员 / 负责宿管实际办理 |
| `/housing/applications/{id}/cancel` | POST | 本人或授权管理人员撤销待处理申请 |
| `/housing/history` | GET | 本人或管理范围的住宿历史 |
| `/students` | GET | 授权范围的完整在校 / 归档资料 |
| `/students/{id}/profile` | PATCH | 管理员，`lifecycle,college?,major?,counselor?,reason` |
| `/students/{id}/account` | POST | 管理员补建已有在校学生账号，`newPassword,reason` |
| `/leaders` | GET | 本寝室 / 管理范围的任命历史 |
| `/rooms/{id}/leader` | POST | 管理员 / 宿管，`studentId,reason` |
| `/accounts` | GET / POST | 管理员列出账号 / 创建工作人员：`username,realName,role,initialPassword,buildingIds` |
| `/accounts/{username}/state` | PATCH | 管理员，`enabled,buildingIds,reason` |
| `/accounts/{username}/reset-password` | POST | 管理员，`newPassword,reason` |
| `/account/password` | PUT | 当前用户，`oldPassword,newPassword`，其他会话失效 |
| `/audit` | GET | 管理员，最近 1000 条审计记录 |
| `/settings` | GET / PUT | 管理员，三个业务开关 |
| `/announcements` | GET / POST | 可见公告 / 管理员或宿管发布 `title,content,audience,buildingId?` |
| `/announcements/{id}/read` | POST | 当前用户标记可见公告已读 |
| `/notifications`、`/notifications/{id}/read` | GET / POST | 个人站内消息与已读 |
| `/stays` | GET / POST | 授权列表 / 学生提交 `kind,startAt,endAt,reason,contactPhone?` |
| `/stays/{id}/review`、`/stays/{id}/return` | POST | 授权审批 `approve,note` / 本人或授权管理人登记结束返校 |
| `/visitors`、`/visitors/{id}/leave` | GET / POST | 范围内访客列表 / 宿管登记到访 / 登记离开 |
| `/inspections` | GET / POST | 范围内检查 / 宿管创建 `roomId,kind,score,detail,dueAt` |
| `/inspections/{id}/rectify`、`/review` | POST | 本寝室提交 `note` / 宿管复查 `approve,note` |
| `/inspections/{id}/image?rectification=false` | POST / GET | multipart `image`，工作人员检查照片或本寝室整改照片，按宿舍鉴权 |
| `/bills` | GET / POST | 本人或管理范围账单 / 管理员收费，学生和宿舍二选一 |
| `/bills/{id}/payments` | GET / POST | 授权查看流水 / 管理员登记 `kind,amount,method,referenceNo,reason` |
| `/bills/{id}/void` | POST | 管理员作废无流水账单，`note` |
| `/assets`、`/assets/{id}/state` | GET / POST / PATCH | 范围内资产；宿管登记或处置 |
| `/assets/{id}/issue` | POST | 宿管为本寝室学生领用，`studentId,note` |
| `/handovers`、`/handovers/{id}/return` | GET / POST | 范围内交接 / 宿管归还 `condition,note` |
| `/repairs/{id}/dispatch` | POST | 管理员 / 负责宿管，`assignee,priority,note` |
| `/repairs/{id}/events` | GET | 工单有权限的查看者读取处理记录 |
| `/repairs/{id}/start`、`/finish` | POST | 已指派维修人员 / 范围内管理人员，`note` |
| `/repairs/{id}/review` | POST | 报修人或当前宿舍长，`approve,note,rating?` |
| `/imports/template` | GET | 管理员下载 XLSX 模板 |
| `/imports/preview` | POST | 管理员 multipart `file`，2MB / 单表 / 500 行以内，无公式 |
| `/imports/{id}/confirm` | POST | 创建预览的管理员整批确认，15 分钟过期且仅一次 |
| `/allocations/preview` | POST | 管理员，`studentIds,buildingIds`，返回分配建议 |
| `/allocations/{id}/confirm` | POST | 创建预览的管理员确认，逐项检查床位版本，整批事务 |

报修列表、提交与附件使用 `/api/repairs`，列表字段采用数据库列名。兼容的状态修改接口也要检查派单和验收顺序。

费用以元接收、整数分存储。`PAYMENT` 为缴费登记，`REFUND` 为退款登记；凭据号必须全局唯一。只有管理员能确认收退款，系统不发起真实第三方支付。

学籍状态 `ENROLLED/GRADUATED/SUSPENDED/WITHDRAWN`；申请 `CHECKIN/TRANSFER/CHECKOUT`；审批 `PENDING/APPROVED/REJECTED/COMPLETED/CANCELLED`；报修 `待处理→处理中→待验收→已完成`，验收失败回到处理中。

## 分页列表与选择项

业务页面使用 `GET /api/business/lists/{resource}?page=1&size=20&search=`，返回 `{items,total,page,size}`。`page` 为 1 至 1000000，`size` 为 1 至 100，搜索最多 200 字符。权限、搜索、总数统计和分页在 SQL 中完成；总数只包含当前用户有权查看且匹配搜索的记录。排序固定并包含唯一编号。搜索将 `%`、`_` 作为普通字符处理，不接受客户端 SQL 或任意排序表达式。

`resource` 支持 `students`、`rooms`、`beds`、`applications`、`history`、`leaders`、`accounts`、`audit`、`announcements`、`notifications`、`stays`、`visitors`、`inspections`、`bills`、`assets`、`handovers`、`repairs`。床位搜索不会匹配无权查看的学生身份字段，审计和消息列表也支持分页。

`GET /api/business/options/{source}?page=1&size=50&search=&ids=&unhoused=false` 返回 `{items,total,page,size}`。`source` 支持 `students,rooms,buildings,workers,beds`；每页最多 100，搜索最多 100 字符并按名称／编号前缀匹配，`ids` 为逗号分隔的已选编号（维修人员为账号），最多 500 个。学生选项的 `unhoused=true` 仅返回未入住的在校学生。编号回查同样受权限约束。表单和批量分配均使用这些分页选项。

`GET /api/business/options?include=students,rooms` 仅用于兼容，当前表单使用上述分页接口。

列表可增加精确 `state`、`building`（完整楼栋名称）、`from`、`to`（含首尾日的 `YYYY-MM-DD` 日期）。不适用的筛选项返回 400；具体日期含义见[查询与事务说明](OPTIMIZATIONS.md)。管理员还可使用 `resource=consistency` 分页查询住宿异常报告，检查过程不自动修改数据。

数组列表接口保留兼容，复用相同的 SQL 权限规则。页面使用分页列表，需要完整数据时由管理员调用导出接口。

启动通过 `business_migration` 的版本 1 跳过已完成的住宿接入，版本 2 执行 `db/performance-indexes.sql` 索引升级。事务按涉及的学生、房间和业务记录加锁；导出采用同一事务快照和流式工作簿。详情见[查询与事务说明](OPTIMIZATIONS.md)。

