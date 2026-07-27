<script setup lang="ts">
import {
  ElAlert,
  ElButton,
  ElCard,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'

import { AdminApiError, listPlatformAccounts, type PlatformAccount } from '@/api/admin'
import { CatalogApiError } from '@/api/catalog'
import {
  getProject,
  listProjectMemberships,
  removeProjectMembership,
  type ProjectMembership,
  type ProjectRole,
  upsertProjectMembership,
} from '@/api/projects'

defineOptions({ name: 'ProjectMembershipView' })

const route = useRoute()
const projectId = computed(() => String(route.params.projectId ?? ''))
const accounts = shallowRef<PlatformAccount[]>([])
const memberships = shallowRef<ProjectMembership[]>([])
const projectRole = ref<ProjectRole | null>(null)
const loadState = ref<'loading' | 'success' | 'error'>('loading')
const errorMessage = ref('')
const isMutating = ref(false)
const membershipForm = reactive<{ actorId: string; role: ProjectRole }>({ actorId: '', role: 'MEMBER' })
let activeRequest: AbortController | null = null

const canManageMembers = computed(() => projectRole.value === 'ADMIN')
const availableAccounts = computed(() => accounts.value.filter((account) => account.enabled))

async function loadMemberships() {
  activeRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  loadState.value = 'loading'
  errorMessage.value = ''
  try {
    const [project, listedMemberships, listedAccounts] = await Promise.all([
      getProject(projectId.value, request.signal),
      listProjectMemberships(projectId.value, request.signal),
      listPlatformAccounts(request.signal),
    ])
    if (activeRequest !== request) return
    projectRole.value = project.current_role
    memberships.value = listedMemberships
    accounts.value = listedAccounts
    loadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || activeRequest !== request) return
    memberships.value = []
    accounts.value = []
    projectRole.value = null
    loadState.value = 'error'
    errorMessage.value =
      error instanceof AdminApiError || error instanceof CatalogApiError
        ? error.message
        : '暂时无法读取项目成员。'
  } finally {
    if (activeRequest === request) activeRequest = null
  }
}

async function saveMembership() {
  if (!canManageMembers.value || isMutating.value || !membershipForm.actorId) return
  try {
    isMutating.value = true
    await upsertProjectMembership(projectId.value, membershipForm.actorId, membershipForm.role)
    ElMessage.success('项目成员已保存。')
    membershipForm.actorId = ''
    membershipForm.role = 'MEMBER'
    await loadMemberships()
  } catch (error) {
    ElMessage.error(error instanceof CatalogApiError ? error.message : '项目成员保存未完成，请稍后重试。')
  } finally {
    isMutating.value = false
  }
}

async function removeMembership(membership: ProjectMembership) {
  if (!canManageMembers.value || isMutating.value) return
  try {
    await ElMessageBox.confirm(
      `移除“${membership.actor_id}”后，该账号将看不到当前项目及其视频数据。`,
      '确认移除项目成员',
      { confirmButtonText: '移除成员', cancelButtonText: '取消', type: 'warning' },
    )
    isMutating.value = true
    await removeProjectMembership(projectId.value, membership.actor_id)
    ElMessage.success('项目成员已移除。')
    await loadMemberships()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof CatalogApiError ? error.message : '项目成员移除未完成，请稍后重试。')
  } finally {
    isMutating.value = false
  }
}

function accountLabel(account: PlatformAccount) {
  return `${account.username}（${account.role === 'ADMIN' ? '管理员' : '普通用户'}）`
}

function asProjectMembership(row: unknown): ProjectMembership {
  return row as ProjectMembership
}

watch(projectId, () => void loadMemberships(), { immediate: true })
onUnmounted(() => activeRequest?.abort())
</script>

<template>
  <nav class="breadcrumb" aria-label="面包屑">
    <RouterLink :to="{ name: 'project-list' }">项目</RouterLink>
    <span aria-hidden="true">/</span>
    <span>项目成员</span>
  </nav>

  <section class="page-heading" aria-labelledby="members-title">
    <div>
      <p class="app-eyebrow">项目访问范围</p>
      <h1 id="members-title">项目成员</h1>
      <p>为内部账号分配当前项目的访问范围。项目角色不改变账号的全局管理员或普通用户身份。</p>
    </div>
    <ElButton :loading="loadState === 'loading'" @click="loadMemberships">刷新</ElButton>
  </section>

  <ElAlert
    title="成员变更会立即影响新的项目请求"
    description="移除成员不会删除视频或账号，只取消其对当前项目的访问。项目必须至少保留一位项目管理员。"
    type="info"
    :closable="false"
    show-icon
  />

  <ElCard v-if="canManageMembers" class="detail-card" shadow="never">
    <template #header>添加或更新成员</template>
    <ElForm class="membership-form" label-position="top" @submit.prevent="saveMembership">
      <ElFormItem label="平台账号">
        <ElSelect v-model="membershipForm.actorId" filterable placeholder="选择已启用的内部账号">
          <ElOption
            v-for="account in availableAccounts"
            :key="account.username"
            :label="accountLabel(account)"
            :value="account.username"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="项目角色">
        <ElSelect v-model="membershipForm.role">
          <ElOption label="普通用户" value="MEMBER" />
          <ElOption label="项目管理员" value="ADMIN" />
        </ElSelect>
      </ElFormItem>
      <ElButton native-type="submit" type="primary" :disabled="!membershipForm.actorId" :loading="isMutating">
        保存成员
      </ElButton>
    </ElForm>
  </ElCard>

  <ElAlert
    v-else-if="loadState === 'success'"
    class="catalog-alert"
    title="当前不是该项目管理员"
    description="只有当前项目的管理员可以调整成员。"
    type="warning"
    :closable="false"
    show-icon
  />

  <div v-if="loadState === 'loading'" class="catalog-notice" role="status">正在读取项目成员…</div>
  <div v-else-if="loadState === 'error'" class="catalog-error" role="alert">
    <span>{{ errorMessage }}</span>
    <ElButton @click="loadMemberships">重试</ElButton>
  </div>

  <ElCard v-else-if="memberships.length > 0" class="table-card" shadow="never">
    <ElTable :data="memberships" row-key="membership_id">
      <ElTableColumn prop="actor_id" label="账号" min-width="240" />
      <ElTableColumn label="项目角色" width="140">
        <template #default="{ row }">
          <ElTag :type="row.role === 'ADMIN' ? 'primary' : 'info'">
            {{ row.role === 'ADMIN' ? '项目管理员' : '普通用户' }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="110"><template #default>有效</template></ElTableColumn>
      <ElTableColumn v-if="canManageMembers" label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <ElButton
            link
            type="danger"
            :aria-label="`移除项目成员：${row.actor_id}`"
            :disabled="isMutating"
            @click="removeMembership(asProjectMembership(row))"
          >
            移除
          </ElButton>
        </template>
      </ElTableColumn>
    </ElTable>
  </ElCard>
  <ElEmpty v-else-if="loadState === 'success'" description="当前项目尚未分配成员。" />
</template>
