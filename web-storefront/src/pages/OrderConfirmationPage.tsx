import { useParams, Link } from 'react-router-dom'
import { CheckCircle } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { orderService } from '@/services/orderService'
import { formatDate, formatPrice } from '@/utils/format'
import { LoadingSpinner } from '@/components/common/LoadingSpinner'

export function OrderConfirmationPage() {
  const { orderId } = useParams<{ orderId: string }>()
  const { data: order, isLoading } = useQuery({
    queryKey: ['order', orderId],
    queryFn: () => orderService.getOrder(orderId!),
    enabled: !!orderId,
  })

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="max-w-lg mx-auto text-center py-16">
      <div className="inline-flex text-green-500 mb-4"><CheckCircle className="w-20 h-20" /></div>
      <h1 className="text-3xl font-bold text-gray-900 mb-2">Order Confirmed!</h1>
      <p className="text-gray-500 mb-1">Thank you for your purchase.</p>
      {order && (
        <>
          <p className="text-sm font-mono text-gray-400 mb-2">{order.id}</p>
          <p className="text-sm text-gray-500 mb-2">{formatDate(order.createdAt)}</p>
          <p className="text-lg font-semibold text-gray-800 mb-8">Total: {formatPrice(order.total)}</p>
        </>
      )}
      <div className="flex gap-4 justify-center flex-wrap">
        <Link to="/" className="bg-blue-600 text-white px-6 py-2 rounded-lg hover:bg-blue-700">Continue Shopping</Link>
        <Link to="/orders" className="bg-white border border-gray-300 text-gray-700 px-6 py-2 rounded-lg hover:bg-gray-50">View Orders</Link>
      </div>
    </div>
  )
}
