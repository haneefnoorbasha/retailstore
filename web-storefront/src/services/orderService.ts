import { api } from './api'
import type { Order, PlaceOrderRequest } from '@/types'

interface PagedOrders {
  content: Order[]
  totalElements: number
  totalPages: number
}

export const orderService = {
  placeOrder: (request: PlaceOrderRequest) =>
    api.post<Order>('/api/v1/orders', request).then(r => r.data),

  getOrder: (orderId: string) =>
    api.get<Order>(`/api/v1/orders/${orderId}`).then(r => r.data),

  getCustomerOrders: (customerId: string, page = 0, size = 10) =>
    api.get<PagedOrders>(`/api/v1/orders/customer/${customerId}`, {
      params: { page, size },
    }).then(r => r.data),

  cancelOrder: (orderId: string, reason?: string) =>
    api.post<Order>(`/api/v1/orders/${orderId}/cancel`, null, {
      params: { reason },
    }).then(r => r.data),
}
