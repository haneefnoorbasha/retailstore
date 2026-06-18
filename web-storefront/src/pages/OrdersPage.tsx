import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Package } from 'lucide-react'
import { orderService } from '@/services/orderService'
import { useAppStore } from '@/store/useAppStore'
import { formatDate, formatPrice, ORDER_STATUS_LABELS, ORDER_STATUS_COLORS } from '@/utils/format'
import { LoadingSpinner } from '@/components/common/LoadingSpinner'
import { EmptyState } from '@/components/common/EmptyState'

export function OrdersPage() {
  const { customerId } = useAppStore()
  const { data, isLoading } = useQuery({
    queryKey: ['orders', customerId],
    queryFn: () => orderService.getCustomerOrders(customerId),
  })

  if (isLoading) return <LoadingSpinner />
  const orders = data?.content ?? []

  if (orders.length === 0) {
    return (
      <EmptyState icon={<Package className="w-16 h-16" />}
        title="No orders yet" description="Your orders will appear here after checkout."
        action={<Link to="/" className="bg-blue-600 text-white px-6 py-2 rounded-lg hover:bg-blue-700">Start Shopping</Link>} />
    )
  }

  return (
    <div className="max-w-3xl mx-auto">
      <h1 className="text-2xl font-bold mb-6">Your Orders</h1>
      <div className="space-y-4">
        {orders.map(order => (
          <div key={order.id} className="bg-white rounded-xl border border-gray-100 p-5">
            <div className="flex items-center justify-between mb-3">
              <span className="font-mono text-sm text-gray-500">{order.id.slice(0, 8)}…</span>
              <span className={`text-xs px-2 py-1 rounded-full font-medium ${ORDER_STATUS_COLORS[order.status] ?? 'bg-gray-100 text-gray-700'}`}>
                {ORDER_STATUS_LABELS[order.status] ?? order.status}
              </span>
            </div>
            <div className="space-y-1 mb-3">
              {order.lineItems.map((li, i) => (
                <div key={i} className="flex justify-between text-sm">
                  <span className="text-gray-700">{li.productName} × {li.quantity}</span>
                  <span className="text-gray-500">{formatPrice(li.lineTotal)}</span>
                </div>
              ))}
            </div>
            <div className="flex justify-between items-center pt-3 border-t border-gray-100">
              <span className="text-sm text-gray-400">{formatDate(order.createdAt)}</span>
              <span className="font-bold">{formatPrice(order.total)}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
