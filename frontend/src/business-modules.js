export const labels = {
    AUTO: '自动判断',
    MANUAL: '人工调整',
    LEGACY: '历史记录',
    WAITING_ASSIGNMENT: '待派单',
    ASSIGNED: '已派单',
    REPAIR_SUBMIT: '提交报修',
    REPAIR_DISPATCH: '派单 / 改派',
    REPAIR_START: '开始维修',
    REPAIR_FINISH: '提交验收',
    REPAIR_ACCEPT: '验收通过',
    REPAIR_REOPEN: '退回重修',
    ROOM_COUNTS: '人数、床位或历史记录异常',
    MISSING_ROOM: '学生关联的宿舍不存在',
    AVAILABLE: '空闲',
    RESERVED: '预留',
    OCCUPIED: '已入住',
    DISABLED: '停用',
    ACTIVE: '正常',
    MAINTENANCE: '维修中',
    CLOSED: '已关闭',
    ENROLLED: '在校',
    GRADUATED: '毕业',
    SUSPENDED: '休学',
    WITHDRAWN: '退学',
    CHECKIN: '入住',
    TRANSFER: '调宿',
    CHECKOUT: '退宿',
    PENDING: '待审核',
    APPROVED: '已批准',
    REJECTED: '未通过',
    COMPLETED: '已办理',
    CANCELLED: '已撤销',
    ADMIN: '管理员',
    MANAGER: '楼栋宿管',
    COUNSELOR: '辅导员',
    WORKER: '维修人员',
    STUDENT: '学生',
    ALL: '全校',
    BUILDING: '指定楼栋',
    HOLIDAY: '假期留宿',
    OVERNIGHT_OUT: '外宿',
    LATE_RETURN: '晚归',
    RETURNED: '已返校',
    HYGIENE: '卫生检查',
    SAFETY: '安全检查',
    OPEN: '待整改',
    SUBMITTED: '待复查',
    ACCOMMODATION: '住宿费',
    WATER: '水费',
    ELECTRIC: '电费',
    DAMAGE: '赔偿',
    PAYMENT: '缴费',
    REFUND: '退款',
    CASH: '现金',
    BANK: '银行转账',
    WECHAT: '微信',
    ALIPAY: '支付宝',
    OTHER: '其他',
    VOID: '已作废',
    FURNITURE: '家具设备',
    KEY: '钥匙',
    REMOTE: '遥控器',
    ISSUED: '已领用',
    REPAIR: '维修中',
    LOST: '丢失',
    RETIRED: '报废',
    GOOD: '完好',
    DAMAGED: '损坏',
    NORMAL: '普通',
    URGENT: '紧急'
}
export const translate = value => labels[value] || value
export const choices = (...values) => values.map(value => ({value, label: translate(value)}))
export const field = (key, label, type = 'text', extra = {}) => ({key, label, type, ...extra})
const reason = () => field('reason', '原因 / 说明', 'textarea', {required: true, max: 500})
const note = () => field('note', '处理说明', 'textarea', {required: true, max: 1000})
const student = () => field('studentId', '学生', 'select', {source: 'students', required: true, number: true})
const room = () => field('roomId', '宿舍', 'select', {source: 'rooms', required: true, number: true})
const bed = () => field('bedId', '目标床位', 'select', {source: 'beds', number: true})
const state = (...v) => field('state', '状态', 'select', {options: choices(...v), required: true})
export const modules = {
    consistency: {
        title: '住宿数据核对', roles: ['admin'],
        columns: [['dormitory_number','宿舍'],['issue','异常类型'],['capacity','容量'],['current_count','登记人数'],['actual_count','实际人数'],['occupied_beds','已占床位'],['inventory_capacity','床位总数'],['open_history','有效入住历史'],['leaders','宿舍长人数'],['invalid_beds','床位归属异常']]
    },
    beds: {
        title: '床位管理',
        path: '/beds',
        roles: ['admin', 'manager', 'counselor', 'student'],
        columns: [['dormitory_number', '宿舍'], ['bed_number', '床号'], ['gender', '性别'], ['room_state', '房间状态'], ['state', '床位状态'], ['student_name', '入住学生'], ['reservation_until', '预留到期']]
    },
    applications: {
        title: '入住 / 调宿 / 退宿',
        path: '/housing/applications',
        roles: ['admin', 'manager', 'counselor', 'student'],
        columns: [['id', '申请编号'], ['name', '学生'], ['kind', '类型'], ['target_room', '目标宿舍'], ['bed_number', '床号'], ['reason', '申请原因'], ['state', '进度'], ['review_note', '审批意见'], ['created_at', '提交时间']]
    },
    history: {
        title: '住宿历史',
        path: '/housing/history',
        roles: ['admin', 'manager', 'counselor', 'student'],
        columns: [['name', '学生'], ['student_number', '学号'], ['room_number', '宿舍'], ['bed_number', '床号'], ['checkin_at', '入住时间'], ['reason', '入住原因'], ['handled_by', '入住经办人'], ['checkout_at', '退宿时间'], ['checkout_reason', '退宿原因'], ['checkout_by', '退宿经办人']]
    },
    rooms: {
        title: '房间停用与维修',
        roles: ['admin', 'manager'],
        columns: [['dormitory_number', '宿舍'], ['capacity', '容量'], ['current_count', '入住人数'], ['room_state', '房间状态'], ['state_reason', '状态说明']]
    },
    archives: {
        title: '学籍与住宿档案',
        path: '/students',
        roles: ['admin', 'manager', 'counselor'],
        columns: [['student_id', '学号'], ['name', '姓名'], ['grade', '年级'], ['class_name', '班级'], ['college', '学院'], ['major', '专业'], ['counselor', '辅导员'], ['dormitory_number', '宿舍'], ['lifecycle', '学籍状态'], ['archive_reason', '归档原因']]
    },
    leaders: {
        title: '宿舍长任命与交接',
        path: '/leaders',
        roles: ['admin', 'manager', 'student'],
        columns: [['dormitory_number', '宿舍'], ['name', '宿舍长'], ['student_number', '学号'], ['appointed_at', '任命时间'], ['ended_at', '离任时间'], ['actor', '经办人'], ['reason', '说明']]
    },
    accounts: {
        title: '账号与楼栋权限',
        path: '/accounts',
        roles: ['admin'],
        columns: [['username', '账号'], ['real_name', '姓名'], ['role', '角色'], ['enabled', '账号启用'], ['buildingIds', '管理楼栋编号'], ['force_password_change', '下次登录须改密']]
    },
    announcements: {
        title: '通知公告',
        path: '/announcements',
        roles: ['admin', 'manager', 'counselor', 'worker', 'student'],
        columns: [['title', '标题'], ['content', '内容'], ['audience', '范围'], ['author', '发布人'], ['created_at', '发布时间'], ['is_read', '已读']]
    },
    notifications: {
        title: '消息与待办',
        path: '/notifications',
        roles: ['admin', 'manager', 'counselor', 'worker', 'student'],
        columns: [['title', '事项'], ['content', '内容'], ['is_read', '已读'], ['created_at', '时间']]
    },
    stays: {
        title: '留宿 / 外宿 / 晚归',
        path: '/stays',
        roles: ['admin', 'manager', 'counselor', 'student'],
        columns: [['name', '学生'], ['dormitory_number', '宿舍'], ['kind', '类型'], ['start_at', '开始时间'], ['end_at', '预计结束'], ['reason', '原因'], ['contact_phone', '联系号码'], ['state', '进度'], ['review_note', '审核意见'], ['actual_return_at', '实际返校']]
    },
    visitors: {
        title: '访客登记',
        path: '/visitors',
        roles: ['admin', 'manager', 'student'],
        columns: [['visitor_name', '访客'], ['phone', '联系号码'], ['host_name', '被访学生'], ['dormitory_number', '宿舍'], ['arrived_at', '到访时间'], ['expected_leave_at', '预计离开'], ['left_at', '实际离开'], ['operator', '登记人']]
    },
    inspections: {
        title: '卫生与安全检查',
        path: '/inspections',
        roles: ['admin', 'manager', 'student'],
        columns: [['dormitory_number', '宿舍'], ['kind', '检查类型'], ['score', '评分'], ['detail', '问题 / 检查项目'], ['due_at', '整改期限'], ['state', '状态'], ['rectify_note', '整改说明'], ['review_note', '复查意见']]
    },
    bills: {
        title: '住宿费用台账',
        path: '/bills',
        roles: ['admin', 'manager', 'student'],
        columns: [['id', '账单编号'], ['name', '学生'], ['room_number', '宿舍'], ['kind', '费用类型'], ['period', '所属期间'], ['amount_cents', '应收金额'], ['net_paid_cents', '实收净额'], ['due_at', '缴费期限'], ['description', '项目说明'], ['state', '状态']]
    },
    assets: {
        title: '家具设备与钥匙',
        path: '/assets',
        roles: ['admin', 'manager', 'student'],
        columns: [['code', '资产编号'], ['name', '名称'], ['category', '类别'], ['dormitory_number', '所属宿舍'], ['state', '状态'], ['description', '说明']]
    },
    handovers: {
        title: '领用与退宿交接',
        path: '/handovers',
        roles: ['admin', 'manager', 'student'],
        columns: [['asset_name', '物品'], ['code', '编号'], ['name', '领用学生'], ['issued_at', '领用时间'], ['returned_at', '归还时间'], ['return_condition', '归还情况'], ['return_note', '说明']]
    },
    batch: {title: '新生导入与批量分配', roles: ['admin'], columns: []},
    audit: {
        title: '业务操作记录',
        path: '/audit',
        roles: ['admin'],
        columns: [['actor', '操作人'], ['action', '操作'], ['resource_type', '对象类型'], ['resource_id', '对象编号'], ['detail_json', '记录详情'], ['created_at', '时间']]
    },
    settings: {title: '住宿业务规则', path: '/settings', roles: ['admin'], columns: []},
    repairs: {
        title: '报修工单',
        path: '!repairs',
        roles: ['admin', 'manager', 'worker', 'student'],
        columns: [['id', '工单编号'], ['dormitory_number', '宿舍'], ['student_name', '报修人'], ['repair_type', '类型'], ['description', '故障描述'], ['dispatch_state', '派单状态'], ['status', '维修进度'], ['assignee', '负责人'], ['priority', '优先级'], ['priority_source', '判断来源'], ['priority_reason', '判断 / 调整依据'], ['completion_note', '完成说明'], ['review_note', '验收意见']]
    }
}

export function creation(kind, role) {
    if (!modules[kind]?.roles.includes(role)) return null
    const staff = ['admin', 'manager'].includes(role)
    switch (kind) {
        case 'applications':
            return role === 'student' ? {
                title: '提交住宿申请',
                fields: [field('kind', '申请类型', 'select', {
                    options: choices('CHECKIN', 'TRANSFER', 'CHECKOUT'),
                    required: true
                }), bed(), reason()],
                path: '/housing/applications'
            } : null
        case 'leaders':
            return staff ? {
                title: '任命宿舍长',
                fields: [room(), student(), reason()],
                path: f => `/rooms/${f.roomId}/leader`,
                omit: ['roomId']
            } : null
        case 'accounts':
            return {
                title: '创建工作人员账号',
                fields: [field('username', '账号（建议英文开头）', 'text', {
                    required: true,
                    max: 50
                }), field('realName', '姓名', 'text', {
                    required: true,
                    max: 50
                }), field('role', '角色', 'select', {
                    options: choices('MANAGER', 'COUNSELOR', 'WORKER'),
                    required: true
                }), field('initialPassword', '初始密码', 'password', {
                    required: true,
                    minlength: 8,
                    max: 72
                }), field('buildingIds', '负责楼栋（宿管填写）', 'select', {
                    source: 'buildings',
                    multiple: true,
                    number: true
                })],
                path: '/accounts'
            }
        case 'announcements':
            return staff ? {
                title: '发布公告',
                fields: [field('title', '标题', 'text', {
                    required: true,
                    max: 120
                }), field('content', '公告内容', 'textarea', {
                    required: true,
                    max: 5000
                }), field('audience', '发布范围', 'select', {
                    options: choices(...(role === 'admin' ? ['ALL', 'BUILDING'] : ['BUILDING'])),
                    required: true
                }), field('buildingId', '目标楼栋', 'select', {source: 'buildings', number: true})],
                path: '/announcements'
            } : null
        case 'stays':
            return role === 'student' ? {
                title: '提交留宿 / 外宿登记',
                fields: [field('kind', '类型', 'select', {
                    options: choices('HOLIDAY', 'OVERNIGHT_OUT', 'LATE_RETURN'),
                    required: true
                }), field('startAt', '开始时间', 'datetime-local', {required: true}), field('endAt', '预计结束 / 返校时间', 'datetime-local', {required: true}), field('contactPhone', '联系号码', 'tel', {max: 11}), reason()],
                path: '/stays'
            } : null
        case 'visitors':
            return staff ? {
                title: '登记访客',
                fields: [room(), student(), field('visitorName', '访客姓名', 'text', {
                    required: true,
                    max: 50
                }), field('phone', '联系号码', 'tel', {
                    required: true,
                    max: 11
                }), field('expectedLeaveAt', '预计离开时间', 'datetime-local', {required: true}), field('note', '备注', 'textarea', {max: 500})],
                path: '/visitors'
            } : null
        case 'inspections':
            return staff ? {
                title: '登记宿舍检查',
                fields: [room(), field('kind', '检查类型', 'select', {
                    options: choices('HYGIENE', 'SAFETY'),
                    required: true
                }), field('score', '评分', 'number', {
                    required: true,
                    min: 0,
                    max: 100
                }), field('detail', '检查项目与问题说明', 'textarea', {
                    required: true,
                    max: 2000
                }), field('dueAt', '整改期限', 'datetime-local', {required: true})],
                path: '/inspections',
                photo: true
            } : null
        case 'bills':
            return role === 'admin' ? {
                title: '生成费用账单',
                help: '单个学生与宿舍分摊二选一；宿舍账单按当前入住学生分摊，尾差按分处理。',
                fields: [{...student(), required: false}, room() && {
                    ...room(),
                    required: false
                }, field('kind', '费用类型', 'select', {
                    options: choices('ACCOMMODATION', 'WATER', 'ELECTRIC', 'DAMAGE'),
                    required: true
                }), field('period', '所属期间', 'text', {
                    required: true,
                    max: 40
                }), field('amount', '总金额（元）', 'number', {
                    required: true,
                    min: .01,
                    step: .01
                }), field('dueAt', '缴费期限', 'date', {required: true}), field('description', '收费项目说明', 'textarea', {
                    required: true,
                    max: 500
                })],
                path: '/bills'
            } : null
        case 'assets':
            return staff ? {
                title: '添加资产 / 钥匙',
                fields: [room(), field('code', '唯一资产编号', 'text', {
                    required: true,
                    max: 80
                }), field('name', '物品名称', 'text', {
                    required: true,
                    max: 100
                }), field('category', '类别', 'select', {
                    options: choices('FURNITURE', 'KEY', 'REMOTE', 'OTHER'),
                    required: true
                }), field('description', '说明', 'textarea', {max: 500})],
                path: '/assets'
            } : null
        case 'repairs':
            return role === 'student' ? {
                title: '提交报修',
                fields: [field('repairType', '报修类型', 'select', {
                    options: ['灯具', '下水拥堵', '桌椅报修', '门窗报修', '其他'].map(value => ({
                        value,
                        label: value
                    })), required: true
                }), field('description', '问题描述', 'textarea', {required: true, max: 2000})],
                path: '!repairs',
                multipart: true,
                photo: true
            } : null
        default:
            return null
    }
}

export {reason, note, student, room, bed, state}
