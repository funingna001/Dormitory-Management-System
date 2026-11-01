"""Create a new SQLite demo database with entirely fictional records."""
from pathlib import Path
import sqlite3

root = Path(__file__).resolve().parent.parent
database = root / 'demo.db'
try:
    with database.open('xb'):
        pass
except FileExistsError:
    raise SystemExit('demo.db already exists; refusing to overwrite it.')

with sqlite3.connect(database) as connection:
    connection.executescript((root / 'src/main/resources/db/schema-sqlite.sql').read_text(encoding='utf-8'))
    connection.executemany('INSERT INTO building(name,gender) VALUES(?,?)', [('示例一号楼', '男'), ('示例二号楼', '女')])
    connection.executemany('INSERT INTO dormitory(dormitory_number,building,room_type,capacity,current_count,gender) VALUES(?,?,?,?,?,?)', [
        ('示例一号楼-0101', '示例一号楼', '四人间', 4, 2, '男'),
        ('示例一号楼-0102', '示例一号楼', '四人间', 4, 0, '男'),
        ('示例二号楼-0101', '示例二号楼', '四人间', 4, 1, '女')])
    connection.executemany('INSERT INTO student(name,student_id,grade,class_name,gender,phone,dormitory_number) VALUES(?,?,?,?,?,?,?)', [
        ('示例学生甲', 'DEMO001', '大一', '示例班级', '男', '', '示例一号楼-0101'),
        ('示例学生乙', 'DEMO002', '大二', '示例班级', '男', '', '示例一号楼-0101'),
        ('示例学生丙', 'DEMO003', '大一', '示例班级', '女', '', '示例二号楼-0101')])
    connection.execute('INSERT INTO repair_request(student_id,student_name,dormitory_number,repair_type,description,status) VALUES(?,?,?,?,?,?)',
                       ('DEMO001', '示例学生甲', '示例一号楼-0101', '水电维修', '演示：照明需要检查', '待处理'))
print('Created demo.db with fictional records. Start the backend using the sqlite profile.')
