const sizes = { sm: 'h-4 w-4', md: 'h-8 w-8', lg: 'h-12 w-12' }
export function LoadingSpinner({ size = 'md' }: { size?: 'sm'|'md'|'lg' }) {
  return (
    <div className="flex justify-center items-center py-12">
      <div className={`${sizes[size]} animate-spin rounded-full border-4 border-blue-100 border-t-blue-600`} />
    </div>
  )
}
