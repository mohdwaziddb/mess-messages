import { useColorScheme } from 'react-native';
import { create } from 'zustand';

export type ThemeMode = 'system' | 'dark' | 'light';

const base = {
  radius: { sm: 10, md: 14, lg: 20, xl: 28, pill: 999 },
  space: (n: number) => n * 4,
  font: {
    title: { fontSize: 28, fontWeight: '700' as const, letterSpacing: -0.5 },
    h2: { fontSize: 20, fontWeight: '700' as const, letterSpacing: -0.2 },
    h3: { fontSize: 16, fontWeight: '600' as const },
    body: { fontSize: 15, fontWeight: '400' as const },
    small: { fontSize: 13, fontWeight: '400' as const },
    tiny: { fontSize: 11, fontWeight: '500' as const, letterSpacing: 0.3 },
    mono: { fontFamily: 'monospace', fontSize: 14 },
  },
};

const dark = {
  ...base,
  dark: true,
  c: {
    bg: '#0B0E14',
    surface: '#131824',
    surface2: '#1A2130',
    border: '#252D3D',
    text: '#EEF2F8',
    textDim: '#93A0B5',
    textFaint: '#5D6A80',
    primary: '#5B8CFF',
    primaryText: '#FFFFFF',
    primarySoft: 'rgba(91,140,255,0.16)',
    accent: '#2DD4BF',
    accentSoft: 'rgba(45,212,191,0.14)',
    success: '#34D399',
    successSoft: 'rgba(52,211,153,0.14)',
    warning: '#FBBF24',
    warningSoft: 'rgba(251,191,36,0.14)',
    danger: '#F87171',
    dangerSoft: 'rgba(248,113,113,0.14)',
    bubbleOut: '#3B6FF0',
    bubbleOutText: '#FFFFFF',
    bubbleIn: '#1C2333',
    bubbleInText: '#EEF2F8',
    overlay: 'rgba(0,0,0,0.6)',
  },
};

const light: typeof dark = {
  ...base,
  dark: false,
  c: {
    bg: '#F5F7FB',
    surface: '#FFFFFF',
    surface2: '#EEF2F8',
    border: '#E1E6EF',
    text: '#0E1420',
    textDim: '#56627A',
    textFaint: '#8E99AE',
    primary: '#2F6BFF',
    primaryText: '#FFFFFF',
    primarySoft: 'rgba(47,107,255,0.10)',
    accent: '#0EA5A0',
    accentSoft: 'rgba(14,165,160,0.10)',
    success: '#10B981',
    successSoft: 'rgba(16,185,129,0.10)',
    warning: '#D97706',
    warningSoft: 'rgba(217,119,6,0.10)',
    danger: '#DC2626',
    dangerSoft: 'rgba(220,38,38,0.08)',
    bubbleOut: '#2F6BFF',
    bubbleOutText: '#FFFFFF',
    bubbleIn: '#FFFFFF',
    bubbleInText: '#0E1420',
    overlay: 'rgba(10,15,25,0.45)',
  },
};

export type Theme = typeof dark;

export const useThemeMode = create<{ mode: ThemeMode; setMode: (m: ThemeMode) => void }>(set => ({
  mode: 'system',
  setMode: mode => set({ mode }),
}));

export function useTheme(): Theme {
  const scheme = useColorScheme();
  const mode = useThemeMode(s => s.mode);
  const isDark = mode === 'system' ? scheme !== 'light' : mode === 'dark';
  return isDark ? dark : light;
}

/** Stable pleasant avatar colour derived from an id (not from the name). */
const palette = ['#5B8CFF', '#2DD4BF', '#F472B6', '#FBBF24', '#A78BFA', '#34D399', '#FB923C', '#60A5FA', '#F87171', '#22D3EE'];
export function avatarColor(id: string): string {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0;
  return palette[h % palette.length];
}
