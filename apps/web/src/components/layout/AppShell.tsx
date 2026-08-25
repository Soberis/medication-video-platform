import { PanelLeftClose, PanelLeftOpen } from 'lucide-react'
import type { ReactNode } from 'react'

export interface AppShellProps {
  children: ReactNode
  collapsed?: boolean
  inspector?: ReactNode
  sidebar: ReactNode
}

/**
 * Canonical application frame. Route-aware navigation belongs in the caller;
 * this component owns only the permanent sidebar/workspace/inspector geometry.
 */
export function AppShell({ children, collapsed = false, inspector, sidebar }: AppShellProps) {
  return (
    <div className="app-frame" data-sidebar-collapsed={collapsed || undefined}>
      <a
        className="skip-link"
        href="#main-content"
        onClick={() => document.getElementById('main-content')?.focus({ preventScroll: true })}
      >
        跳到主要内容
      </a>
      {sidebar}
      <Workspace inspector={inspector}>{children}</Workspace>
    </div>
  )
}

export interface SidebarProps {
  brand: ReactNode
  children: ReactNode
  collapsed: boolean
  footer?: ReactNode
  onCollapsedChange: (collapsed: boolean) => void
}

export function Sidebar({ brand, children, collapsed, footer, onCollapsedChange }: SidebarProps) {
  return (
    <aside className="app-sidebar" aria-label="应用侧栏">
      <div className="sidebar-header">
        {brand}
        <button
          className="sidebar-toggle"
          type="button"
          aria-label={collapsed ? '展开侧栏' : '收起侧栏'}
          aria-expanded={!collapsed}
          aria-controls="sidebar-navigation"
          onClick={() => onCollapsedChange(!collapsed)}
        >
          {collapsed ? (
            <PanelLeftOpen aria-hidden="true" size={16} strokeWidth={1.8} />
          ) : (
            <PanelLeftClose aria-hidden="true" size={16} strokeWidth={1.8} />
          )}
        </button>
      </div>
      <div id="sidebar-navigation" className="sidebar-navigation">
        {children}
      </div>
      {footer ? <div className="sidebar-footer">{footer}</div> : null}
    </aside>
  )
}

export interface WorkspaceProps {
  children: ReactNode
  inspector?: ReactNode
}

export function Workspace({ children, inspector }: WorkspaceProps) {
  return (
    <div className="workspace" data-has-inspector={inspector ? true : undefined}>
      <main id="main-content" className="workspace-main" tabIndex={-1}>
        <div className="workspace-content">{children}</div>
      </main>
      {inspector}
    </div>
  )
}

export interface InspectorProps {
  ariaLabel: string
  children: ReactNode
}

export function Inspector({ ariaLabel, children }: InspectorProps) {
  return (
    <aside className="workspace-inspector" aria-label={ariaLabel}>
      {children}
    </aside>
  )
}
