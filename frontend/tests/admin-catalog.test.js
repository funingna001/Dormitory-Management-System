import test from 'node:test'
import assert from 'node:assert/strict'
import { adminGroups, searchModules } from '../src/admin-catalog.js'
import { navigationFor, canAccessPage } from '../src/permissions.js'

test('administrator lands on the portal and every module belongs to exactly one category', () => {
  assert.equal(navigationFor('admin')[0][0], 'home')
  const keys = adminGroups('admin').flatMap(group => group.modules.map(module => module.key))
  assert.equal(new Set(keys).size, keys.length)
  assert.deepEqual([...keys].sort(), navigationFor('admin').map(([key]) => key).filter(key => key !== 'home').sort())
  for (const key of keys) assert.equal(canAccessPage(key, 'admin'), true)
})
test('search finds tasks by names and aliases across categories', () => {
  assert.ok(searchModules('admin', '派单').some(module => module.key === 'repairs'))
  assert.ok(searchModules('admin', 'AI').some(module => module.key === 'overview'))
  assert.ok(searchModules('admin', 'ＡＩ').some(module => module.key === 'overview'))
  assert.ok(searchModules('admin', '费用').some(module => module.key === 'bills'))
  assert.ok(searchModules('admin', '密码 重置').some(module => module.key === 'accounts'))
  assert.deepEqual(searchModules('admin', '不存在的事项'), [])
  assert.deepEqual(searchModules('admin', '  '), [])
})
test('portal and its search do not expose admin entries to other roles', () => {
  for (const role of ['student', 'worker', 'manager', 'counselor', undefined]) {
    assert.deepEqual(adminGroups(role), [])
    assert.deepEqual(searchModules(role, '账号'), [])
    assert.equal(canAccessPage('home', role), false)
  }
  assert.equal(navigationFor('student')[0][0], 'profile')
})
