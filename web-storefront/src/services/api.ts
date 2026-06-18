import axios from 'axios'
import { authService } from './authService'

const CLIENT_CHANNEL = import.meta.env.VITE_CLIENT_CHANNEL ?? 'WEB'
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

export const api = axios.create({
  baseURL: BASE_URL,
  timeout: 10_000,
  headers: {
    'Content-Type': 'application/json',
    'X-Client-Channel': CLIENT_CHANNEL,
  },
})

api.interceptors.request.use(async config => {
  config.headers['X-Correlation-Id'] = Math.random().toString(36).slice(2, 11)

  const token = await authService.getAccessToken()
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }

  return config
})

api.interceptors.response.use(
  res => res,
  err => {
    if (err.response?.status === 401) {
      authService.login()
    }
    return Promise.reject(err)
  }
)
