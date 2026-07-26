<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElMessage, ElTag } from 'element-plus'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const isLoginRoute = computed(() => route.name === 'login')
const projectId = computed(() => String(route.params.projectId ?? ''))
const sessionsRoute = computed(() => ({ name: 'session-list', params: { projectId: projectId.value } }))
const assetsRoute = computed(() => ({ name: 'asset-list', params: { projectId: projectId.value } }))
const annotationTasksRoute = computed(() => ({ name: 'annotation-task-list', params: { projectId: projectId.value } }))
const foldersRoute = computed(() => ({ name: 'folder-management', params: { projectId: projectId.value } }))
const membershipsRoute = computed(() => ({ name: 'project-memberships', params: { projectId: projectId.value } }))
const isAdministrator = computed(() => auth.session?.role === 'ADMIN')
const isManagementRoute = computed(() =>
  ['folder-management', 'project-memberships', 'upload-progress', 'account-management'].includes(
    String(route.name),
  ),
)

async function logout() {
  try {
    await auth.logout()
    await router.replace({ name: 'login' })
  } catch {
    ElMessage.error('退出登录未完成，请稍后重试。')
  }
}

function closeManagementMenu(event: MouseEvent) {
  const navigationMenu = (event.currentTarget as HTMLElement).closest('details')
  navigationMenu?.removeAttribute('open')
}
</script>

<template>
  <RouterView v-if="isLoginRoute" />
  <div v-else class="app-shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <header class="app-header">
      <RouterLink
        class="app-brand"
        :to="{ name: 'project-list' }"
        active-class=""
        exact-active-class=""
        aria-label="返回项目列表"
      >
        配药视频数据管理平台
      </RouterLink>
      <nav class="primary-nav" aria-label="主导航">
        <RouterLink :to="{ name: 'project-list' }">项目</RouterLink>
        <RouterLink v-if="projectId" :to="sessionsRoute">配药操作</RouterLink>
        <RouterLink v-if="projectId" :to="assetsRoute">视频资产</RouterLink>
        <RouterLink v-if="projectId" :to="annotationTasksRoute">标注任务</RouterLink>
        <RouterLink :to="{ name: 'local-upload' }">本地上传</RouterLink>
        <details class="nav-more" :class="{ 'nav-more-active': isManagementRoute }">
          <summary>管理</summary>
          <div class="nav-more-menu">
            <RouterLink v-if="projectId" :to="foldersRoute" @click="closeManagementMenu">文件夹</RouterLink>
            <RouterLink
              v-if="projectId && isAdministrator"
              :to="membershipsRoute"
              @click="closeManagementMenu"
            >
              项目成员
            </RouterLink>
            <RouterLink to="/uploads" @click="closeManagementMenu">上传排障</RouterLink>
            <RouterLink
              v-if="isAdministrator"
              :to="{ name: 'account-management' }"
              @click="closeManagementMenu"
            >
              账号管理
            </RouterLink>
          </div>
        </details>
      </nav>
      <div v-if="auth.session" class="account-summary">
        <span>{{ auth.session.username }}</span>
        <ElTag size="small" :type="auth.session.role === 'ADMIN' ? 'primary' : 'info'">
          {{ auth.session.role === 'ADMIN' ? '管理员' : '普通用户' }}
        </ElTag>
        <ElButton link type="primary" :loading="auth.loading" @click="logout">退出登录</ElButton>
      </div>
    </header>

    <main id="main-content" tabindex="-1">
      <RouterView />
    </main>
  </div>
</template>
