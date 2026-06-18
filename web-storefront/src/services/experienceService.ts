import { api } from './api'
import type { HomepageResponse, Product } from '@/types'

export const experienceService = {
  getHomepage: (customerId: string, featuredCount = 8) =>
    api.get<HomepageResponse>('/api/v1/experience/homepage', {
      params: { customerId, featuredCount },
    }).then(r => r.data),

  getProductDetail: (id: string) =>
    api.get<Product>(`/api/v1/experience/products/${id}`).then(r => r.data),

  getCartSummary: (customerId: string) =>
    api.get(`/api/v1/experience/cart/${customerId}/summary`).then(r => r.data),
}
