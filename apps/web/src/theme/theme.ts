import type { ThemeConfig } from 'antd'

import { appTokens } from '@/theme/tokens'

/** Maps the canonical tokens to Ant Design and the CSS-only presentation layer. */
export const appTheme: ThemeConfig = {
  cssVar: {
    key: 'medication-video-platform',
  },
  token: {
    colorPrimary: appTokens.color.brand,
    colorInfo: appTokens.color.brand,
    colorSuccess: appTokens.color.success,
    colorWarning: appTokens.color.warning,
    colorError: appTokens.color.danger,
    colorText: appTokens.color.textPrimary,
    colorTextSecondary: appTokens.color.textSecondary,
    colorTextTertiary: appTokens.color.textRegular,
    colorBgLayout: appTokens.color.page,
    colorBgContainer: appTokens.color.surface,
    colorBgElevated: appTokens.color.surface,
    colorFillAlter: appTokens.color.surfaceMuted,
    colorBorder: appTokens.color.border,
    colorBorderSecondary: appTokens.color.borderSubtle,
    borderRadius: appTokens.radius.default,
    borderRadiusSM: appTokens.radius.small,
    borderRadiusLG: appTokens.radius.large,
    fontFamily: appTokens.typography.fontFamily,
    fontSize: appTokens.typography.baseSize,
  },
}

const cssTokenBridge = {
  '--app-color-brand': appTokens.color.brand,
  '--app-color-brand-hover': appTokens.color.brandHover,
  '--app-color-brand-subtle': appTokens.color.brandSubtle,
  '--app-color-page': appTokens.color.page,
  '--app-color-surface': appTokens.color.surface,
  '--app-color-surface-muted': appTokens.color.surfaceMuted,
  '--app-color-surface-hover': appTokens.color.surfaceHover,
  '--app-color-text-primary': appTokens.color.textPrimary,
  '--app-color-text-regular': appTokens.color.textRegular,
  '--app-color-text-secondary': appTokens.color.textSecondary,
  '--app-color-border': appTokens.color.border,
  '--app-color-border-subtle': appTokens.color.borderSubtle,
  '--app-color-success': appTokens.color.success,
  '--app-color-success-surface': appTokens.color.successSurface,
  '--app-color-warning': appTokens.color.warning,
  '--app-color-warning-surface': appTokens.color.warningSurface,
  '--app-color-danger': appTokens.color.danger,
  '--app-color-danger-surface': appTokens.color.dangerSurface,
  '--app-color-neutral-surface': appTokens.color.neutralSurface,
  '--app-color-focus': appTokens.color.focus,
  '--app-space-4': `${appTokens.spacing[4]}px`,
  '--app-space-8': `${appTokens.spacing[8]}px`,
  '--app-space-12': `${appTokens.spacing[12]}px`,
  '--app-space-16': `${appTokens.spacing[16]}px`,
  '--app-space-24': `${appTokens.spacing[24]}px`,
  '--app-space-32': `${appTokens.spacing[32]}px`,
  '--app-radius-small': `${appTokens.radius.small}px`,
  '--app-radius-default': `${appTokens.radius.default}px`,
  '--app-radius-large': `${appTokens.radius.large}px`,
  '--app-shadow-card': appTokens.shadow.card,
  '--app-shadow-raised': appTokens.shadow.raised,
  '--app-shadow-floating': appTokens.shadow.floating,
  '--app-font-family': appTokens.typography.fontFamily,
  '--app-font-size-base': `${appTokens.typography.baseSize}px`,
  '--app-font-size-helper': `${appTokens.typography.helperSize}px`,
  '--app-font-size-heading-1': `${appTokens.typography.heading.h1}px`,
  '--app-font-size-heading-2': `${appTokens.typography.heading.h2}px`,
  '--app-font-size-heading-3': `${appTokens.typography.heading.h3}px`,
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
