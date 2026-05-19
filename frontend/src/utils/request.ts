import axios, { type AxiosInstance, type AxiosResponse } from 'axios'
import { STORAGE_KEYS } from '@/constants'
import { refreshAccessToken, clearAuthAndRedirect } from './token-refresh'

const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

// 创建 axios 实例
const service: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// 请求拦截器 - 注入 Token
service.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器 - 处理 401 刷新 Token + 审核状态码
let isRefreshing = false
let pendingRequests: Array<(token: string) => void> = []

service.interceptors.response.use(
  (response: AxiosResponse) => {
    const { code, data, message } = response.data
    if (code === 0) {
      return data
    }
    // 业务错误码：1001=审核中，1002=封禁（前端处理，不弹 toast）
    if (code === 1001 || code === 1002) {
      const error = new Error(message || '状态异常') as any
      error.code = code
      return Promise.reject(error)
    }
    return Promise.reject(new Error(message || '请求未完成'))
  },
  async (error) => {
    const originalRequest = error.config

    if (error.response?.status === 401 && !originalRequest._retry) {
      const refreshToken = localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN)
      if (!refreshToken) {
        clearAuthAndRedirect()
        return Promise.reject(error)
      }

      if (isRefreshing) {
        return new Promise((resolve) => {
          pendingRequests.push((token: string) => {
            originalRequest.headers.Authorization = `Bearer ${token}`
            resolve(service(originalRequest))
          })
        })
      }

      originalRequest._retry = true
      isRefreshing = true

      try {
        const newToken = await refreshAccessToken()
        if (!newToken) {
          clearAuthAndRedirect()
          return Promise.reject(error)
        }

        pendingRequests.forEach((cb) => cb(newToken))
        pendingRequests = []

        originalRequest.headers.Authorization = `Bearer ${newToken}`
        return service(originalRequest)
      } catch {
        clearAuthAndRedirect()
        return Promise.reject(error)
      } finally {
        isRefreshing = false
      }
    }

    const msg = error.response?.data?.message || error.message || '网络异常'
    return Promise.reject(new Error(msg))
  }
)

export default service
