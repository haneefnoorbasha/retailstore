import type { ReactNode } from 'react'
import { Navbar } from './Navbar'
export function Layout({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen flex flex-col bg-gray-50">
      <Navbar />
      <main className="flex-1 max-w-7xl mx-auto w-full px-4 sm:px-6 lg:px-8 py-8">{children}</main>
      <footer className="bg-white border-t border-gray-100 py-6">
        <p className="text-center text-sm text-gray-400">© {new Date().getFullYear()} RetailStore Platform</p>
      </footer>
    </div>
  )
}
