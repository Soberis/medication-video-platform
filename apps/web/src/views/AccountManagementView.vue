<script setup lang="ts">
import {
  ElAlert,
  ElButton,
  ElCard,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { onMounted, onUnmounted, reactive, ref, shallowRef } from 'vue'

import {
  AdminApiError,
  changePlatformAccountEnabled,
  createPlatformAccount,
  listPlatformAccounts,
  type PlatformAccount,
} from '@/api/admin'

defineOptions({ name: 'AccountManagementView' })

const accounts = shallowRef<PlatformAccount[]>([])
const loadState = ref<'loading' | 'success' | 'error'>('loading')
const errorMessage = ref('')
const isSubmitting = ref(false)
const isChanging = ref(false)
const newAccount = reactive({ username: '', password: '' })
let activeRequest: AbortController | null = null

async function loadAccounts() {
  activeRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  loadState.value = 'loading'
  errorMessage.value = ''
  try {
    accounts.value = await listPlatformAccounts(request.signal)
    if (activeRequest === request) loadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || activeRequest !== request) return
    accounts.value = []
    loadState.value = 'error'
    errorMessage.value = error instanceof AdminApiError ? error.message : '暂时无法读取平台账号。'
  } finally {
    if (activeRequest === request) activeRequest = null
  }
}

async function createAccount() {
  if (isSubmitting.value) return
  const username = newAccount.username.trim()
  if (!/^[A-Za-z0-9][A-Za-z0-9._:@-]{2,127}$/.test(username)) {
    ElMessage.error('账号名需为 3 至 128 位字母、数字或 . _ : @ -。')
    return
  }
  if (newAccount.password.length < 12 || newAccount.password.length > 72) {
    ElMessage.error('初始密码长度需为 12 至 72 个字符。')
    return
  }
  try {
    isSubmitting.value = true
    await createPlatformAccount(username, newAccount.password)
    newAccount.username = ''
    newAccount.password = ''
    ElMessage.success('普通用户账号已创建。请在对应项目中添加该成员。')
    await loadAccounts()
  } catch (error) {
    ElMessage.error(error instanceof AdminApiError ? error.message : '账号创建未完成，请稍后重试。')
  } finally {
    isSubmitting.value = false
  }
}

async function toggleEnabled(account: PlatformAccount) {
  if (isChanging.value || account.role !== 'MEMBER') return
  const nextEnabled = !account.enabled
  try {
    await ElMessageBox.confirm(
      nextEnabled
        ? `启用“${account.username}”后，该账号可以再次登录。`
        : `停用“${account.username}”会阻止其创建新的登录会话；已有会话按当前会话策略自然失效。`,
      nextEnabled ? '确认启用账号' : '确认停用账号',
      {
        confirmButtonText: nextEnabled ? '启用账号' : '停用账号',
        cancelButtonText: '取消',
        type: nextEnabled ? 'info' : 'warning',
      },
    )
    isChanging.value = true
    await changePlatformAccountEnabled(account.username, nextEnabled)
    ElMessage.success(nextEnabled ? '账号已启用。' : '账号已停用。')
    await loadAccounts()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof AdminApiError ? error.message : '账号状态更新未完成，请稍后重试。')
  } finally {
    isChanging.value = false
  }
}

function asPlatformAccount(row: unknown): PlatformAccount {
  return row as PlatformAccount
}

onMounted(() => void loadAccounts())
onUnmounted(() => activeRequest?.abort())
</script>

<template>
  <section class="page-heading" aria-labelledby="accounts-title">
    <div>
      <p class="app-eyebrow">内部访问控制</p>
      <h1 id="accounts-title">平台账号</h1>
      <p>创建和启停内部普通用户。账号创建后仍需加入具体项目，才能查看或处理该项目的视频数据。</p>
    </div>
    <ElButton :loading="loadState === 'loading'" @click="loadAccounts">刷新</ElButton>
  </section>

  <ElAlert
    title="账号与项目权限分开管理"
    description="账号仅决定是否能登录；项目 Membership 决定能够看到和操作哪些项目。医院边端与媒体 Worker 不使用此账号体系。"
    type="info"
    :closable="false"
    show-icon
  />

  <ElCard class="detail-card" shadow="never">
    <template #header>创建普通用户</template>
    <ElForm label-position="top" class="account-form" @submit.prevent="createAccount">
      <ElFormItem label="账号名">
        <ElInput v-model="newAccount.username" autocomplete="username" maxlength="128" />
      </ElFormItem>
      <ElFormItem label="初始密码">
        <ElInput
          v-model="newAccount.password"
          type="password"
          autocomplete="new-password"
          show-password
          maxlength="72"
        />
      </ElFormItem>
      <p class="form-hint">初始密码不会显示在账号列表、日志或审计记录中。当前切片只创建普通用户。</p>
      <ElButton native-type="submit" type="primary" :loading="isSubmitting">创建账号</ElButton>
    </ElForm>
  </ElCard>

  <div v-if="loadState === 'loading'" class="catalog-notice" role="status">正在读取平台账号…</div>
  <div v-else-if="loadState === 'error'" class="catalog-error" role="alert">
    <span>{{ errorMessage }}</span>
    <ElButton @click="loadAccounts">重试</ElButton>
  </div>

  <ElCard v-else class="table-card" shadow="never">
    <ElTable :data="accounts" row-key="username">
      <ElTableColumn prop="username" label="账号名" min-width="220" />
      <ElTableColumn label="全局角色" width="130">
        <template #default="{ row }">
          <ElTag :type="row.role === 'ADMIN' ? 'primary' : 'info'">
            {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="登录状态" width="120">
        <template #default="{ row }">
          <ElTag :type="row.enabled ? 'success' : 'danger'">{{ row.enabled ? '启用' : '停用' }}</ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <ElButton
            v-if="row.role === 'MEMBER'"
            link
            :type="row.enabled ? 'danger' : 'primary'"
            :disabled="isChanging"
            @click="toggleEnabled(asPlatformAccount(row))"
          >
            {{ row.enabled ? '停用' : '启用' }}
          </ElButton>
          <span v-else class="cell-secondary">初始管理员</span>
        </template>
      </ElTableColumn>
    </ElTable>
  </ElCard>
</template>
