import test from 'node:test'
import assert from 'node:assert/strict'
import { navigationFor, canAccessPage, moduleForRole } from '../src/permissions.js'
import { creation } from '../src/business-modules.js'

test('students cannot open administrative pages or creation dialogs', () => {
  for (const page of ['overview', 'students', 'dormitories', 'buildings', 'accounts', 'archives', 'rooms', 'batch', 'audit', 'settings', 'consistency']) {
    assert.equal(canAccessPage(page, 'student'), false, page)
    assert.equal(creation(page, 'student'), null, page)
  }
})
test('student services use personal titles and exclude management actions', () => {
  const navigation = Object.fromEntries(navigationFor('student'))
  assert.equal(navigation.bills, '我的费用账单')
  assert.equal(navigation.leaders, '本宿舍宿舍长')
  assert.equal(navigation.beds, '床位查询')
  assert.match(moduleForRole('bills', 'student').description, /本人/)
  for (const page of ['beds', 'bills', 'leaders', 'assets', 'visitors', 'inspections']) {
    assert.equal(creation(page, 'student'), null, page)
  }
  assert.ok(creation('applications', 'student'))
  assert.ok(creation('repairs', 'student'))
})
test('worker navigation is limited to work and messages', () => {
  assert.deepEqual(navigationFor('worker').map(([key]) => key), ['announcements', 'notifications', 'repairs'])
  assert.equal(canAccessPage('bills', 'worker'), false)
})
test('administrators retain management navigation and actions', () => {
  for (const page of ['overview', 'students', 'accounts', 'batch', 'audit', 'settings']) {
    assert.equal(canAccessPage(page, 'admin'), true, page)
  }
  assert.equal(moduleForRole('bills', 'admin').title, '住宿费用台账')
  assert.ok(creation('accounts', 'admin'))
})
test('unknown and signed-out roles have no business access', () => {
  for (const role of [undefined, null, '', 'unknown']) {
    assert.deepEqual(navigationFor(role), [])
    assert.equal(moduleForRole('bills', role), null)
    assert.equal(canAccessPage('overview', role), false)
  }
})
