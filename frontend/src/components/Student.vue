<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api,currentUser } from '../api'
const student=ref(null),dormitory=ref(null),error=ref(''),notice=ref(''),busy=ref(false)
const form=reactive({name:'',grade:'大一',className:'',gender:'男',phone:''})
async function load(){try{const data=await api('/me');student.value=data.student;dormitory.value=data.dormitory;if(data.student)Object.keys(form).forEach(k=>form[k]=data.student[k]||'')}catch(e){error.value=e.message}}
async function save(){busy.value=true;error.value='';try{await api('/me/profile',{method:'PUT',body:form});currentUser.value=(await api('/auth/me')).user;notice.value='个人资料已保存';await load()}catch(e){error.value=e.message}finally{busy.value=false}}
onMounted(load)
</script>
<template>
  <div class="page-heading"><div><span class="eyebrow">MY RESIDENCE</span><h1>我的宿舍</h1><p class="muted">查看住宿安排，完善你的个人资料</p></div></div><div v-if="error" class="alert" role="alert">{{error}}</div><div v-if="notice" class="notice">{{notice}}</div>
  <section class="card padded residence"><div><span class="eyebrow">住宿信息</span><h2>{{student?.dormitoryNumber||'暂未分配宿舍'}}</h2><p v-if="dormitory" class="muted">{{dormitory.room_type}} · {{dormitory.current_count}} / {{dormitory.capacity}} 床位</p><p v-else class="muted">完善资料后，请联系管理员安排入住。</p></div><span v-if="student?.dormitoryLeader" class="badge">宿舍长</span></section>
  <section class="card padded"><h2>个人资料</h2><p class="muted">学号：{{currentUser.username}}</p><form class="form-grid" @submit.prevent="save"><label>姓名<input v-model="form.name" required maxlength="50"></label><label>年级<select v-model="form.grade"><option v-for="g in ['大一','大二','大三','大四']" :key="g">{{g}}</option></select></label><label>班级<input v-model="form.className" maxlength="50"></label><label>性别<select v-model="form.gender"><option>男</option><option>女</option></select></label><label>手机号<input v-model="form.phone" required pattern="1[3-9][0-9]{9}" maxlength="11"></label><div class="form-footer"><button class="primary" :disabled="busy">保存个人资料</button></div></form></section>
</template>
