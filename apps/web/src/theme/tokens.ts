/**
 * The only canonical visual values for the Web application. Component-level
 * exceptions belong in their feature CSS only when they describe real media or
 * visualization semantics, not a second product theme.
 */
const typographyScaleTokens = {
  fontFamily:
    "Inter, 'Noto Sans SC', 'PingFang SC', 'Microsoft YaHei', system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
  monoFamily: "ui-monospace, 'SFMono-Regular', Menlo, Consolas, 'Liberation Mono', monospace",
  baseSize: 14,
  helperSize: 12,
  heading: {
    h1: 24,
    h2: 16,
    h3: 14,
  },
  weight: {
    regular: 400,
    medium: 500,
    semibold: 600,
  },
  lineHeight: {
    single: 1,
    heading: 1.2,
    metric: 1.25,
    compact: 1.35,
    control: 1.4,
    metadata: 1.45,
    body: 1.5,
    prose: 1.55,
    relaxed: 1.65,
  },
} as const

/** Semantic roles expose the frozen scale without creating a second set of values. */
const typographyTokens = {
  ...typographyScaleTokens,
  semantic: {
    pageTitle: {
      size: typographyScaleTokens.heading.h1,
      weight: typographyScaleTokens.weight.semibold,
      lineHeight: typographyScaleTokens.lineHeight.heading,
    },
    sectionTitle: {
      size: typographyScaleTokens.heading.h2,
      weight: typographyScaleTokens.weight.semibold,
      lineHeight: typographyScaleTokens.lineHeight.compact,
    },
    body: {
      size: typographyScaleTokens.baseSize,
      weight: typographyScaleTokens.weight.regular,
      lineHeight: typographyScaleTokens.lineHeight.body,
    },
    label: {
      size: typographyScaleTokens.helperSize,
      weight: typographyScaleTokens.weight.medium,
      lineHeight: typographyScaleTokens.lineHeight.compact,
    },
    metadata: {
      size: typographyScaleTokens.helperSize,
      weight: typographyScaleTokens.weight.regular,
      lineHeight: typographyScaleTokens.lineHeight.metadata,
    },
    caption: {
      size: typographyScaleTokens.helperSize,
      weight: typographyScaleTokens.weight.regular,
      lineHeight: typographyScaleTokens.lineHeight.compact,
    },
  },
} as const

export const appTokens = {
  color: {
    background: '#F6F7F9',
    surface: '#FFFFFF',
    surfaceElevated: '#FFFFFF',
    surfaceMuted: '#F3F4F6',
    surfaceHover: '#ECEFF3',
    foreground: '#161B22',
    foregroundSecondary: '#4B5563',
    foregroundMuted: '#8A94A6',
    border: '#D1D5DB',
    borderSubtle: '#E5E7EB',
    primary: '#315E9B',
    primaryHover: '#264C80',
    primarySubtle: '#E8EEF6',
    primaryForeground: '#FFFFFF',
    focusRing: '#3B82F6',
    success: '#12643B',
    successSurface: '#EAF5EE',
    warning: '#B45309',
    warningSurface: '#FFF7ED',
    destructive: '#B42318',
    destructiveSurface: '#FFF1F0',
    information: '#315E9B',
    informationSurface: '#E8EEF6',
    neutralSurface: '#F3F4F6',
    mediaStageBackground: '#101828',
    mediaStageBorder: '#27364F',
    mediaOverlay: '#8AA9D9',
    mediaOverlayLandmark: '#FFFFFF',
    mediaControlBorder: 'rgb(255 255 255 / 42%)',
    mediaControlBackground: 'rgb(16 24 40 / 78%)',
    mediaControlBackgroundHover: 'rgb(16 24 40 / 92%)',
    sidebarBackground: '#111827',
    sidebarForeground: '#F9FAFB',
    sidebarMuted: '#9CA3AF',
    sidebarBorder: 'rgb(255 255 255 / 10%)',
    sidebarHover: 'rgb(255 255 255 / 7%)',
    sidebarSelected: 'rgb(49 94 155 / 20%)',
    sidebarAccent: '#3B82F6',
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
    floating: '0 12px 28px rgb(17 24 39 / 14%)',
    overlay: '0 20px 48px rgb(17 24 39 / 18%)',
  },
  typography: typographyTokens,
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
    workspaceMaxWide: 2080,
    workspaceMaxUltra: 2560,
    inspector: 320,
  },
} as const
