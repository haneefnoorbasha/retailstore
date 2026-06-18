import type { ReactNode } from 'react'
export function EmptyState({ icon, title, description, action }: {
  icon: ReactNode; title: string; description: string; action?: ReactNode
}) {
  return (
    <div className="text-center py-20">
      <div className="inline-flex text-gray-300 mb-4">{icon}</div>
      <h3 className="text-xl font-semibold text-gray-700 mb-2">{title}</h3>
      <p className="text-gray-500 mb-6 max-w-sm mx-auto">{description}</p>
      {action}
    </div>
  )
}
