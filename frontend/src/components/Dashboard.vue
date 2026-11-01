<script setup>
import { onMounted,ref } from 'vue'
import { api,download } from '../api'
import AiAssistant from './AiAssistant.vue'
const stats=ref(null),error=ref('')
async function load(){try{stats.value=await api('/data/statistics')}catch(e){error.value=e.message}}
async function exportAll(){try{await download('all')}catch(e){error.value=e.message}}
onMounted(load)
</script>
<template>
  <div class="page-heading"><div><span class="eyebrow">OVERVIEW</span><h1>住宿数据概览</h1><p class="muted">校园住宿信息，一目了然</p></div><div class="actions"><button @click="load">刷新数据</button><button class="primary" @click="exportAll">导出全部数据</button></div></div><div v-if="error" class="alert" role="alert">{{error}}</div>
  <template v-if="stats"><div class="stat-grid"><section class="card stat"><span>在档学生</span><strong>{{stats.totalStudents}}<small>人</small></strong><p>全部学生档案</p></section><section class="card stat"><span>宿舍房间</span><strong>{{stats.totalDormitories}}<small>间</small></strong><p>{{Object.keys(stats.buildingStats).length}} 栋宿舍楼</p></section><section v-for="gender in ['男','女']" :key="gender" class="card stat"><span>{{gender}}生人数</span><strong>{{stats.genderStats[gender]||0}}<small>人</small></strong><p>来自学生档案统计</p></section></div>
  <div class="dashboard-grid"><section class="card padded"><h2>年级分布</h2><div v-for="(value,key) in stats.gradeStats" :key="key" class="bar-row"><div><span>{{key}}</span><strong>{{value}} 人</strong></div><div class="bar-track"><div :style="{width:(stats.totalStudents?value/stats.totalStudents*100:0)+'%'}"></div></div></div></section><section class="card padded"><h2>楼栋房间分布</h2><div v-for="(value,key) in stats.buildingStats" :key="key" class="bar-row"><div><span>{{key}}</span><strong>{{value}} 间</strong></div><div class="bar-track teal"><div :style="{width:(stats.totalDormitories?value/stats.totalDormitories*100:0)+'%'}"></div></div></div></section></div>
  <AiAssistant /></template>
</template>
