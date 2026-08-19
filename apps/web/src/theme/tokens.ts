/**
 * The only canonical visual values for the Web application. Component-level
 * exceptions belong in their feature CSS only when they describe real media or
 * visualization semantics, not a second product theme.
 */
export const appTokens = {
  color: {
    brand: '#2f5ec4',
    brandHover: '#244da8',
    brandSubtle: '#e8efff',
    page: '#f3f6fa',
    surface: '#ffffff',
    surfaceMuted: '#f5f7fb',
    surfaceHover: '#f8faff',
    textPrimary: '#172033',
    textRegular: '#475467',
    textSecondary: '#667085',
    border: '#8995a8',
    borderSubtle: '#dce3ee',
    success: '#12643b',
    successSurface: '#eaf5ee',
    warning: '#9a3412',
    warningSurface: '#fff4e8',
    danger: '#b42318',
    dangerSurface: '#fff1f0',
    neutralSurface: '#eef2f6',
    focus: '#3867d6',
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
    small: 4,
    default: 8,
    large: 12,
  },
  shadow: {
    card: '0 12px 36px rgb(28 42 68 / 7%)',
    raised: '0 16px 44px rgb(28 42 68 / 8%)',
    floating: '0 12px 28px rgb(28 42 68 / 14%)',
  },
  typography: {
    fontFamily:
      "Inter, 'Noto Sans SC', 'PingFang SC', 'Microsoft YaHei', system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
    baseSize: 14,
    helperSize: 12,
    heading: {
      h1: 30,
      h2: 24,
      h3: 20,
    },
  },
} as const
