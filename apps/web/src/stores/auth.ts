import { computed, shallowRef } from 'vue'
import { defineStore } from 'pinia'

import {
  AuthApiError,
  getSession,
  login as loginRequest,
  logout as logoutRequest,
  type PlatformSession,
} from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const session = shallowRef<PlatformSession | null>(null)
  const initialized = shallowRef(false)
  const loading = shallowRef(false)
  const isAuthenticated = computed(() => session.value !== null)

  async function loadSession() {
    if (initialized.value) return session.value
    loading.value = true
    try {
      session.value = await getSession()
    } catch (error) {
      if (!(error instanceof AuthApiError) || error.errorCode !== 'UNAUTHENTICATED') throw error
      session.value = null
    } finally {
      initialized.value = true
      loading.value = false
    }
    return session.value
  }

  async function login(username: string, password: string) {
    loading.value = true
    try {
      session.value = await loginRequest(username, password)
      initialized.value = true
    } finally {
      loading.value = false
    }
  }

  async function logout() {
    loading.value = true
    try {
      await logoutRequest()
      session.value = null
      initialized.value = true
    } finally {
      loading.value = false
    }
  }

  return { initialized, isAuthenticated, loading, session, loadSession, login, logout }
})
