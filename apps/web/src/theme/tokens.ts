/**
 * The only canonical visual values for the Web application. Component-level
 * exceptions belong in their feature CSS only when they describe real media or
 * visualization semantics, not a second product theme.
 */
export const appTokens = {
  color: {
    background: '#f7f8fa',
    surface: '#ffffff',
    surfaceElevated: '#ffffff',
    surfaceMuted: '#f2f4f7',
    surfaceHover: '#eef1f6',
    foreground: '#202329',
    foregroundSecondary: '#505763',
    foregroundMuted: '#737b89',
    border: '#c8ced8',
    borderSubtle: '#e2e6ec',
    primary: '#315cba',
    primaryHover: '#274b9b',
    primarySubtle: '#e9eefb',
    primaryForeground: '#ffffff',
    focusRing: '#3e69c7',
    success: '#12643b',
    successSurface: '#eaf5ee',
    warning: '#9a3412',
    warningSurface: '#fff4e8',
    danger: '#b42318',
    dangerSurface: '#fff1f0',
    information: '#315cba',
    informationSurface: '#e9eefb',
    neutralSurface: '#eef2f6',
  },
  spacing: {
    4: 4,
    8: 8,
    12: 12,
    16: 16,
    24: 24,
    32: 32,
  },
  radius: {
    xs: 4,
    sm: 6,
    md: 8,
    lg: 10,
  },
  shadow: {
    floating: '0 12px 28px rgb(24 31 44 / 14%)',
    overlay: '0 20px 48px rgb(24 31 44 / 18%)',
  },
  typography: {
    fontFamily:
      "Inter, 'Noto Sans SC', 'PingFang SC', 'Microsoft YaHei', system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
    baseSize: 14,
    helperSize: 12,
    heading: {
      h1: 24,
      h2: 16,
      h3: 14,
    },
  },
  control: {
    compact: 28,
    standard: 32,
    large: 36,
    input: 32,
    select: 32,
    icon: 28,
  },
  layout: {
    sidebar: 224,
    sidebarCollapsed: 64,
    workspaceMax: 1680,
    inspector: 320,
  },
} as const
