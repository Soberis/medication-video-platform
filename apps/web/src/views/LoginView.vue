<script setup lang="ts">
import { ElAlert, ElButton, ElCard, ElForm, ElFormItem, ElInput } from 'element-plus'
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { AuthApiError } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

defineOptions({ name: 'LoginView' })

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const username = ref('')
const password = ref('')
const errorMessage = ref('')

async function submitLogin() {
  errorMessage.value = ''
  try {
    await auth.login(username.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/projects'
    await router.replace(redirect.startsWith('/') ? redirect : '/projects')
  } catch (error) {
    errorMessage.value = error instanceof AuthApiError ? error.message : '暂时无法完成登录，请稍后重试。'
  } finally {
    password.value = ''
  }
}
</script>

<template>
  <section class="login-page" aria-labelledby="login-title">
    <ElCard class="login-card" shadow="never">
      <p class="app-eyebrow">内部管理端</p>
      <h1 id="login-title">登录数据管理平台</h1>
      <p>仅供平台管理人员使用。医院侧采集设备通过独立凭据自动上传视频，不使用此页面。</p>

      <ElAlert
        v-if="errorMessage"
        class="login-error"
        type="error"
        :title="errorMessage"
        :closable="false"
        show-icon
      />

      <ElForm class="login-form" label-position="top" @submit.prevent="submitLogin">
        <ElFormItem label="账号">
          <ElInput v-model="username" autocomplete="username" maxlength="128" required />
        </ElFormItem>
        <ElFormItem label="密码">
          <ElInput
            v-model="password"
            type="password"
            show-password
            autocomplete="current-password"
            maxlength="256"
            required
          />
        </ElFormItem>
        <ElButton native-type="submit" type="primary" :loading="auth.loading">登录</ElButton>
      </ElForm>
    </ElCard>
  </section>
</template>
