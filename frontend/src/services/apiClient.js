// src/services/apiClient.js
import { api } from 'boot/axios'

/**
 * Централизованное место для:
 * - добавления auth headers (позже)
 * - единых timeouts
 * - обработки ошибок / refresh-token логики (позже)
 * - единых методов get/post/put/delete
 */

// На будущее: сюда можно будет положить токен/сессию
let authToken = null

export function setAuthToken (token) {
  authToken = token
}

export function clearAuthToken () {
  authToken = null
}

// Интерцептор запросов — сюда добавим Authorization, когда появится
api.interceptors.request.use((config) => {
  if (authToken) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${authToken}`
  }
  return config
})

function unwrap (resp) {
  return resp?.data
}

// Единый парсер ошибок с бэка (чтобы UI везде показывал нормальный текст)
export function getErrorMessage (e, fallback = 'Ошибка запроса') {
  return e?.response?.data?.message
    || e?.response?.data?.error
    || e?.message
    || fallback
}

export const apiClient = {
  // GET
  async get (url, config) {
    return unwrap(await api.get(url, config))
  },

  // POST
  async post (url, body, config) {
    return unwrap(await api.post(url, body, config))
  },

  // PUT
  async put (url, body, config) {
    return unwrap(await api.put(url, body, config))
  },

  // PATCH
  async patch (url, body, config) {
    return unwrap(await api.patch(url, body, config))
  },

  // DELETE
  async delete (url, config) {
    return unwrap(await api.delete(url, config))
  }
}
