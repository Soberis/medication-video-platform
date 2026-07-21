<script setup lang="ts">
import { ElButton, ElCard } from 'element-plus'
import { useRouter } from 'vue-router'

defineOptions({ name: 'LocalLoginView' })

const router = useRouter()
const enabled = import.meta.env.DEV

async function selectRole(role: 'admin' | 'member') {
  document.cookie = `mvp_dev_role=${role}; Path=/; SameSite=Lax`
  await router.replace({ name: 'project-list' })
}
</script>

<template>
  <section class="login-page" aria-labelledby="login-title">
    <ElCard class="login-card" shadow="never">
      <p class="app-eyebrow">本地开发身份</p>
      <h2 id="login-title">选择测试角色</h2>
      <p>仅用于合成数据开发。浏览器不保存令牌；项目访问与操作权限仍由后端 Membership 校验。</p>
      <div v-if="enabled" class="login-actions">
        <ElButton type="primary" @click="selectRole('admin')">以管理员进入</ElButton>
        <ElButton @click="selectRole('member')">以普通用户进入</ElButton>
      </div>
      <p v-else class="form-hint">生产环境由医院 OIDC 提供登录身份。</p>
    </ElCard>
  </section>
</template>
