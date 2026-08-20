import type { ThemeConfig } from 'antd'

import { appTokens } from '@/theme/tokens'

/** Maps the canonical tokens to Ant Design and the CSS-only presentation layer. */
export const appTheme: ThemeConfig = {
  cssVar: {
    key: 'medication-video-platform',
  },
  token: {
    colorPrimary: appTokens.color.primary,
    colorInfo: appTokens.color.information,
    colorSuccess: appTokens.color.success,
    colorWarning: appTokens.color.warning,
    colorError: appTokens.color.destructive,
    colorText: appTokens.color.foreground,
    colorTextSecondary: appTokens.color.foregroundSecondary,
    colorTextTertiary: appTokens.color.foregroundMuted,
    colorBgLayout: appTokens.color.background,
    colorBgContainer: appTokens.color.surface,
    colorBgElevated: appTokens.color.surfaceElevated,
    colorFillAlter: appTokens.color.surfaceMuted,
    colorBorder: appTokens.color.border,
    colorBorderSecondary: appTokens.color.borderSubtle,
    borderRadius: appTokens.radius.sm,
    borderRadiusSM: appTokens.radius.xs,
    borderRadiusLG: appTokens.radius.md,
    fontFamily: appTokens.typography.fontFamily,
    fontSize: appTokens.typography.baseSize,
    controlHeight: appTokens.control.standard,
    controlHeightSM: appTokens.control.compact,
    controlHeightLG: appTokens.control.large,
  },
}

const cssTokenBridge = {
  '--app-color-background': appTokens.color.background,
  '--app-color-surface': appTokens.color.surface,
  '--app-color-surface-elevated': appTokens.color.surfaceElevated,
  '--app-color-surface-muted': appTokens.color.surfaceMuted,
  '--app-color-surface-hover': appTokens.color.surfaceHover,
  '--app-color-foreground': appTokens.color.foreground,
  '--app-color-foreground-secondary': appTokens.color.foregroundSecondary,
  '--app-color-foreground-muted': appTokens.color.foregroundMuted,
  '--app-color-border': appTokens.color.border,
  '--app-color-border-subtle': appTokens.color.borderSubtle,
  '--app-color-primary': appTokens.color.primary,
  '--app-color-primary-hover': appTokens.color.primaryHover,
  '--app-color-primary-subtle': appTokens.color.primarySubtle,
  '--app-color-primary-foreground': appTokens.color.primaryForeground,
  '--app-color-focus-ring': appTokens.color.focusRing,
  '--app-color-success': appTokens.color.success,
  '--app-color-success-surface': appTokens.color.successSurface,
  '--app-color-warning': appTokens.color.warning,
  '--app-color-warning-surface': appTokens.color.warningSurface,
  '--app-color-destructive': appTokens.color.destructive,
  '--app-color-destructive-surface': appTokens.color.destructiveSurface,
  '--app-color-information': appTokens.color.information,
  '--app-color-information-surface': appTokens.color.informationSurface,
  '--app-color-neutral-surface': appTokens.color.neutralSurface,
  // Compatibility aliases for existing business-page CSS during gradual migration.
  '--app-color-brand': appTokens.color.primary,
  '--app-color-brand-hover': appTokens.color.primaryHover,
  '--app-color-brand-subtle': appTokens.color.primarySubtle,
  '--app-color-page': appTokens.color.background,
  '--app-color-text-primary': appTokens.color.foreground,
  '--app-color-text-regular': appTokens.color.foregroundSecondary,
  '--app-color-text-secondary': appTokens.color.foregroundMuted,
  '--app-color-focus': appTokens.color.focusRing,
  '--app-color-danger': appTokens.color.destructive,
  '--app-color-danger-surface': appTokens.color.destructiveSurface,
  '--app-space-4': `${appTokens.spacing[4]}px`,
  '--app-space-8': `${appTokens.spacing[8]}px`,
  '--app-space-12': `${appTokens.spacing[12]}px`,
  '--app-space-16': `${appTokens.spacing[16]}px`,
  '--app-space-24': `${appTokens.spacing[24]}px`,
  '--app-space-32': `${appTokens.spacing[32]}px`,
  '--app-radius-xs': `${appTokens.radius.xs}px`,
  '--app-radius-sm': `${appTokens.radius.sm}px`,
  '--app-radius-md': `${appTokens.radius.md}px`,
  '--app-radius-lg': `${appTokens.radius.lg}px`,
  '--app-radius-small': `${appTokens.radius.xs}px`,
  '--app-radius-default': `${appTokens.radius.sm}px`,
  '--app-radius-large': `${appTokens.radius.lg}px`,
  '--app-shadow-card': 'none',
  '--app-shadow-raised': appTokens.shadow.floating,
  '--app-shadow-floating': appTokens.shadow.floating,
  '--app-shadow-overlay': appTokens.shadow.overlay,
  '--app-font-family': appTokens.typography.fontFamily,
  '--app-font-size-base': `${appTokens.typography.baseSize}px`,
  '--app-font-size-helper': `${appTokens.typography.helperSize}px`,
  '--app-font-size-heading-1': `${appTokens.typography.heading.h1}px`,
  '--app-font-size-heading-2': `${appTokens.typography.heading.h2}px`,
  '--app-font-size-heading-3': `${appTokens.typography.heading.h3}px`,
  '--app-control-height-compact': `${appTokens.control.compact}px`,
  '--app-control-height': `${appTokens.control.standard}px`,
  '--app-control-height-large': `${appTokens.control.large}px`,
  '--app-control-height-input': `${appTokens.control.input}px`,
  '--app-control-height-select': `${appTokens.control.select}px`,
  '--app-control-height-icon': `${appTokens.control.icon}px`,
  '--app-sidebar-width': `${appTokens.layout.sidebar}px`,
  '--app-sidebar-collapsed-width': `${appTokens.layout.sidebarCollapsed}px`,
  '--app-workspace-max-width': `${appTokens.layout.workspaceMax}px`,
  '--app-workspace-max-width-wide': `${appTokens.layout.workspaceMaxWide}px`,
  '--app-workspace-max-width-ultra': `${appTokens.layout.workspaceMaxUltra}px`,
  '--app-inspector-width': `${appTokens.layout.inspector}px`,
} as const

/**
 * Custom layouts use CSS variables, but their values are still derived from
 * tokens.ts instead of duplicated in a stylesheet.
 */
export function applyAppCssVariables() {
  for (const [name, value] of Object.entries(cssTokenBridge)) {
    document.documentElement.style.setProperty(name, value)
  }
}
