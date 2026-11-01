<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api'
const status = ref(null), message = ref(''), result = ref(null), busy = ref(false), error = ref('')
const suggestions = ['当前住宿统计概况', '学生总数是多少？', '当前入住率是多少？', '各楼栋入住情况对比', '剩余房间容量是多少？', '报修工单状态分布']
onMounted(async () => { try { status.value = await api('/ai/status') } catch (e) { error.value = e.message } })
async function ask(question = message.value) {
  if (busy.value || !question.trim()) return
  message.value = question; busy.value = true; error.value = ''; result.value = null
  try { result.value = await api('/ai/chat', { method: 'POST', body: { message: question.trim() } }) }
  catch (e) { error.value = e.retryAfter ? `${e.message}，请在 ${e.retryAfter} 秒后重试。` : e.message }
  finally { busy.value = false }
}
</script>
<template>
  <section class="card padded assistant" :aria-busy="busy">
    <span class="eyebrow">DEEPSEEK · 数据查询</span><h2>管理员统计助手</h2>
    <p class="muted">查询学生、年级、楼栋入住、房间容量和报修汇总。</p>
    <p v-if="status" class="muted">{{ status.configured ? `DeepSeek 已配置 · ${status.model}` : '本地统计模式 · 配置 API Key 后可使用自然语言意图识别' }}</p>
    <p class="muted">DeepSeek 仅接收本次提问以识别查询类型，统计数据保留在本地。请勿在提问中填写姓名、电话或学号。</p>
    <div class="actions quick-questions"><button v-for="question in suggestions" :key="question" :disabled="busy" @click="ask(question)">{{ question }}</button></div>
    <form class="filters" @submit.prevent="ask()">
      <input v-model="message" required maxlength="2000" :disabled="busy" placeholder="例如：各栋宿舍分别住了多少人？" aria-label="统计查询问题">
      <button class="primary" :disabled="busy || !message.trim()">{{ busy ? '正在查询…' : '查询' }}</button>
    </form>
    <div v-if="error" class="alert" role="alert">{{ error }}</div>
    <div v-if="result" aria-live="polite">
      <p><span class="badge">{{ result.source === 'deepseek' ? 'DeepSeek 意图识别' : '本地统计' }}</span> <small>{{ new Date(result.generatedAt).toLocaleString() }}</small></p>
      <p class="muted">{{ result.notice }}</p><pre class="ai-reply">{{ result.reply }}</pre>
      <details><summary>查看本次查询的数据依据与统计口径</summary>
        <p class="muted">学生数为全部在档学生；入住率按房间登记人数/房间总容量计算。剩余容量未扣除维修、停用和预留限制。当前不支持日期区间、个人明细和指定楼栋筛选。</p>
        <div class="stat-grid">
          <div>在档学生：{{ result.statistics.totalStudents }} 人</div><div>房间数：{{ result.statistics.totalDormitories }} 间</div>
          <div>入住人数：{{ result.statistics.residentCount }} 人</div><div>入住率：{{ result.statistics.occupancyRate }}%</div>
          <div>房间总容量：{{ result.statistics.totalCapacity }} 人</div><div>报修总数：{{ result.statistics.totalRepairs }} 条</div>
        </div>
        <pre class="ai-reply">{{ result.statistics.buildingDetails }}</pre>
        <p v-for="item in result.statistics.repairStatus" :key="item.status">报修 · {{ item.status }}：{{ item.count }} 条</p>
      </details>
    </div>
  </section>
</template>
<style scoped>
.quick-questions { flex-wrap: wrap; margin: 16px 0; }
.assistant .filters input { flex: 1; min-width: 180px; }
.assistant details { margin-top: 16px; }
.assistant summary { cursor: pointer; }
.ai-reply { white-space: pre-wrap; overflow-wrap: anywhere; font-family: inherit; }
</style>
