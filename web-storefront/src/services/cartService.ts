import { api } from './api'
import type { AddToCartRequest, Cart } from '@/types'

export const cartService = {
  getCart: (customerId: string) =>
    api.get<Cart>(`/api/v1/carts/${customerId}`).then(r => r.data),

  addItem: (customerId: string, request: AddToCartRequest) =>
    api.post<Cart>(`/api/v1/carts/${customerId}/items`, request).then(r => r.data),

  updateItem: (customerId: string, itemId: string, quantity: number) =>
    api.put<Cart>(`/api/v1/carts/${customerId}/items/${itemId}`, { quantity }).then(r => r.data),

  removeItem: (customerId: string, itemId: string) =>
    api.delete(`/api/v1/carts/${customerId}/items/${itemId}`),

  clearCart: (customerId: string) =>
    api.delete(`/api/v1/carts/${customerId}`),
}
