<script setup>
import { reactive, ref } from 'vue'
import { api, synchronizeSession, refreshCsrf } from '../api'
const emit=defineEmits(['logged-in'])
const busy=ref(false),error=ref(''),pending=ref(false),stamp=ref(Date.now())
const form=reactive({username:'',password:'',captcha:''})
// pending 表示密码已提交成功，但页面还没同步到最新的登录状态。
function refresh(){stamp.value=Date.now();form.captcha=''}
async function complete(){await synchronizeSession();pending.value=false;emit('logged-in')}
// 网络恢复后只同步状态，避免重复登录；密码在首次登录成功后就会清空。
async function recover(){if(busy.value)return;busy.value=true;error.value='';try{await complete()}catch(e){error.value=e.message;if(e.status===401){pending.value=false;refresh()}}finally{busy.value=false}}
async function submit(){if(busy.value||pending.value)return;busy.value=true;error.value='';try{await refreshCsrf();await api('/auth/login',{method:'POST',body:{...form}});pending.value=true;form.password='';form.captcha='';await complete()}catch(e){error.value=e.message;if(e.status===401)pending.value=false}finally{busy.value=false;if(!pending.value)refresh()}}
</script>
<template>
  <div class="login-page"><div class="login-intro"><div class="brand"><span class="brand-mark">D</span>宿舍管理系统</div><div><span class="eyebrow">CAMPUS LIVING</span><h1>让校园生活<br>井然有序。</h1><p>连接学生、宿管与维修服务，<br>让每一间宿舍得到妥善管理。</p></div><small>校园住宿 · 统一管理</small></div>
    <div class="login-panel"><form @submit.prevent="submit"><span class="eyebrow">WELCOME</span><h2>欢迎登录</h2><p class="muted">请输入账号信息，进入你的工作空间。</p>
      <div v-if="error" class="alert" role="alert">{{error}}</div>
      <label>账号 / 学号<input v-model="form.username" required maxlength="50" autocomplete="username" placeholder="请输入账号"></label>
      <label>密码<input v-model="form.password" type="password" required minlength="1" maxlength="72" autocomplete="current-password" placeholder="请输入密码"></label>

      <label>验证码<div class="captcha-row"><input v-model="form.captcha" required maxlength="4" inputmode="numeric" autocomplete="off" placeholder="四位验证码"><button type="button" class="captcha-button" title="刷新验证码" @click="refresh"><img :src="'/api/auth/captcha?t='+stamp" alt="验证码，点击刷新"></button></div></label>
      <button class="primary full" :disabled="busy||pending">{{busy?'正在处理…':'登录系统'}}</button>
      <p v-if="pending" class="notice">登录已完成，状态同步暂时失败，请重新同步。</p><button type="button" :disabled="busy" @click="recover">重新同步登录状态</button>
      <p class="center muted">请联系管理员核验学籍并开通账号。</p>
    </form></div>
  </div>
</template>
