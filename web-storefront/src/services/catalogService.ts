import { api } from './api'
import type { PagedProductResponse, Product, Tag } from '@/types'

export const catalogService = {
  getProducts: (params: { page?: number; size?: number; tags?: string; order?: string }) =>
    api.get<PagedProductResponse>('/api/v1/catalog/products', { params }).then(r => r.data),

  getProduct: (id: string) =>
    api.get<Product>(`/api/v1/catalog/products/${id}`).then(r => r.data),

  getTags: () =>
    api.get<Tag[]>('/api/v1/catalog/tags').then(r => r.data),
}
