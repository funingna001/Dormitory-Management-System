"""Generate portable, additive business tables for MySQL and SQLite."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
tables={
'room_policy': 'room_id INTEGER PRIMARY KEY, state VARCHAR(20) NOT NULL DEFAULT \'ACTIVE\', reason VARCHAR(500), version INTEGER NOT NULL DEFAULT 0',
'bed': "id {pk}, room_id INTEGER NOT NULL, bed_number INTEGER NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', student_id INTEGER UNIQUE, reserved_for INTEGER, reservation_until VARCHAR(40), version INTEGER NOT NULL DEFAULT 0, UNIQUE(room_id,bed_number)",
'student_profile': "student_id INTEGER PRIMARY KEY, lifecycle VARCHAR(20) NOT NULL DEFAULT 'ENROLLED', college VARCHAR(100), major VARCHAR(100), counselor VARCHAR(50), archived_at VARCHAR(40), archive_reason VARCHAR(500)",
'account_policy': "username VARCHAR(50) PRIMARY KEY, enabled INTEGER NOT NULL DEFAULT 1, credential_version INTEGER NOT NULL DEFAULT 0, force_password_change INTEGER NOT NULL DEFAULT 0",
'manager_building': 'username VARCHAR(50) NOT NULL, building_id INTEGER NOT NULL, PRIMARY KEY(username,building_id)',
'housing_history': 'id {pk}, student_id INTEGER NOT NULL, student_number VARCHAR(20) NOT NULL, room_id INTEGER NOT NULL, room_number VARCHAR(80) NOT NULL, bed_id BIGINT NOT NULL, bed_number INTEGER NOT NULL, checkin_at VARCHAR(40) NOT NULL, checkout_at VARCHAR(40), reason VARCHAR(500), handled_by VARCHAR(50) NOT NULL, checkout_reason VARCHAR(500), checkout_by VARCHAR(50)',
'housing_application': "id {pk}, student_id INTEGER NOT NULL, kind VARCHAR(20) NOT NULL, target_bed_id BIGINT, old_bed_id BIGINT, reason VARCHAR(500) NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'PENDING', reviewer VARCHAR(50), review_note VARCHAR(500), created_at VARCHAR(40) NOT NULL, reviewed_at VARCHAR(40), completed_at VARCHAR(40)",
'leader_record': 'id {pk}, room_id INTEGER NOT NULL, student_id INTEGER NOT NULL, appointed_at VARCHAR(40) NOT NULL, ended_at VARCHAR(40), actor VARCHAR(50) NOT NULL, reason VARCHAR(500)',
'audit_log': 'id {pk}, actor VARCHAR(50) NOT NULL, action VARCHAR(80) NOT NULL, resource_type VARCHAR(60) NOT NULL, resource_id VARCHAR(80), detail_json TEXT, created_at VARCHAR(40) NOT NULL',
'repair_work': "repair_id INTEGER PRIMARY KEY, assignee VARCHAR(50), priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL', completion_note VARCHAR(1000), review_note VARCHAR(1000), rating INTEGER, updated_at VARCHAR(40) NOT NULL",
'repair_priority': "repair_id INTEGER PRIMARY KEY, priority_source VARCHAR(20) NOT NULL DEFAULT 'LEGACY', priority_reason VARCHAR(1200) NOT NULL DEFAULT '', suggested_priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL'",
'repair_event': 'id {pk}, repair_id INTEGER NOT NULL, actor VARCHAR(50) NOT NULL, action VARCHAR(40) NOT NULL, note VARCHAR(1000), created_at VARCHAR(40) NOT NULL',
'announcement': "id {pk}, title VARCHAR(120) NOT NULL, content TEXT NOT NULL, audience VARCHAR(20) NOT NULL, building_id INTEGER, author VARCHAR(50) NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED', created_at VARCHAR(40) NOT NULL",
'announcement_receipt': 'announcement_id BIGINT NOT NULL, username VARCHAR(50) NOT NULL, read_at VARCHAR(40) NOT NULL, PRIMARY KEY(announcement_id,username)',
'notification': 'id {pk}, recipient VARCHAR(50) NOT NULL, title VARCHAR(120) NOT NULL, content VARCHAR(1500) NOT NULL, is_read INTEGER NOT NULL DEFAULT 0, created_at VARCHAR(40) NOT NULL',
'stay_request': "id {pk}, student_id INTEGER NOT NULL, kind VARCHAR(30) NOT NULL, start_at VARCHAR(40) NOT NULL, end_at VARCHAR(40) NOT NULL, reason VARCHAR(500) NOT NULL, contact_phone VARCHAR(20), state VARCHAR(20) NOT NULL DEFAULT 'PENDING', reviewer VARCHAR(50), review_note VARCHAR(500), actual_return_at VARCHAR(40), created_at VARCHAR(40) NOT NULL",
'visitor': 'id {pk}, room_id INTEGER NOT NULL, student_id INTEGER NOT NULL, visitor_name VARCHAR(50) NOT NULL, phone VARCHAR(20) NOT NULL, expected_leave_at VARCHAR(40) NOT NULL, arrived_at VARCHAR(40) NOT NULL, left_at VARCHAR(40), operator VARCHAR(50) NOT NULL, note VARCHAR(500)',
'inspection': "id {pk}, room_id INTEGER NOT NULL, kind VARCHAR(20) NOT NULL, score INTEGER NOT NULL, detail VARCHAR(2000) NOT NULL, due_at VARCHAR(40) NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'OPEN', created_by VARCHAR(50) NOT NULL, image_path VARCHAR(200), rectify_image_path VARCHAR(200), rectify_note VARCHAR(1000), review_note VARCHAR(1000), reviewed_by VARCHAR(50), created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL",
'bill': "id {pk}, student_id INTEGER NOT NULL, room_number VARCHAR(80), kind VARCHAR(30) NOT NULL, period VARCHAR(40) NOT NULL, amount_cents BIGINT NOT NULL, due_at VARCHAR(40) NOT NULL, description VARCHAR(500) NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', created_by VARCHAR(50) NOT NULL, created_at VARCHAR(40) NOT NULL, CHECK(amount_cents>0)",
'payment': 'id {pk}, bill_id BIGINT NOT NULL, kind VARCHAR(20) NOT NULL, amount_cents BIGINT NOT NULL, method VARCHAR(30) NOT NULL, reference_no VARCHAR(100) UNIQUE, reason VARCHAR(500) NOT NULL, actor VARCHAR(50) NOT NULL, created_at VARCHAR(40) NOT NULL, CHECK(amount_cents>0)',
'asset': "id {pk}, room_id INTEGER NOT NULL, code VARCHAR(80) NOT NULL UNIQUE, name VARCHAR(100) NOT NULL, category VARCHAR(30) NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', description VARCHAR(500)",
'handover': 'id {pk}, asset_id BIGINT NOT NULL, student_id INTEGER NOT NULL, active_asset_id BIGINT UNIQUE, issued_at VARCHAR(40) NOT NULL, returned_at VARCHAR(40), return_condition VARCHAR(20), issue_note VARCHAR(500), return_note VARCHAR(500), issued_by VARCHAR(50) NOT NULL, returned_by VARCHAR(50)',
'import_batch': "id VARCHAR(40) PRIMARY KEY, owner VARCHAR(50) NOT NULL, kind VARCHAR(20) NOT NULL, payload TEXT NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'PREVIEW', created_at VARCHAR(40) NOT NULL, expires_at VARCHAR(40) NOT NULL",
'business_setting': 'setting_key VARCHAR(80) PRIMARY KEY, setting_value VARCHAR(500) NOT NULL',
'business_migration': 'version INTEGER PRIMARY KEY, applied_at VARCHAR(40) NOT NULL',
'topology_guard': 'id INTEGER PRIMARY KEY',
}
for dialect,pk in [('mysql','BIGINT AUTO_INCREMENT PRIMARY KEY'),('sqlite','INTEGER PRIMARY KEY AUTOINCREMENT')]:
    suffix=' ENGINE=InnoDB DEFAULT CHARSET=utf8mb4' if dialect=='mysql' else ''
    content='\n'.join('CREATE TABLE IF NOT EXISTS '+name+' ('+definition.replace('{pk}',pk)+')'+suffix+';' for name,definition in tables.items())+'\n'
    content += ('INSERT IGNORE' if dialect=='mysql' else 'INSERT OR IGNORE') + ' INTO topology_guard(id) VALUES(1);\n'
    (root/'src/main/resources/db'/('business-'+dialect+'.sql')).write_text(content,encoding='utf-8')
print('Generated additive business schemas: '+str(len(tables))+' tables per database.')
(root/'schema.sql').write_text((root/'src/main/resources/db/schema-mysql.sql').read_text(encoding='utf-8')+'\n'+(root/'src/main/resources/db/business-mysql.sql').read_text(encoding='utf-8'),encoding='utf-8')
