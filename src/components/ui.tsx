import React, { useEffect, useRef } from 'react';
import {
  ActivityIndicator, Animated, Pressable, ScrollView, StatusBar, StyleProp, StyleSheet, Switch, Text, TextInput,
  TextInputProps, View, ViewStyle,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { avatarColor, Theme, useTheme } from '../theme/theme';
import { Icon, IconName } from './Icon';

export function Screen({ children, scroll = true, padded = true, style }: { children: React.ReactNode; scroll?: boolean; padded?: boolean; style?: StyleProp<ViewStyle> }) {
  const t = useTheme();
  const inner = padded ? { paddingHorizontal: 18, paddingBottom: 32 } : null;
  return (
    <View style={[{ flex: 1, backgroundColor: t.c.bg }, style]}>
      <StatusBar barStyle={t.dark ? "light-content" : "dark-content"} />
      {scroll ? <ScrollView contentContainerStyle={inner} keyboardShouldPersistTaps="handled">{children}</ScrollView> : <View style={[{ flex: 1 }, inner]}>{children}</View>}
    </View>
  );
}

export function Header({ title, subtitle, back = true, right }: { title: string; subtitle?: string; back?: boolean; right?: React.ReactNode }) {
  const t = useTheme();
  const nav = useNavigation();
  const insets = useSafeAreaInsets();
  return (
    <View style={{ paddingTop: insets.top + 6, paddingHorizontal: 12, paddingBottom: 10, backgroundColor: t.c.bg, flexDirection: 'row', alignItems: 'center' }}>
      {back && nav.canGoBack() ? (
        <Pressable onPress={() => nav.goBack()} hitSlop={12} style={{ padding: 6, marginRight: 4 }} accessibilityLabel="Back">
          <Icon name="back" color={t.c.text} size={24} />
        </Pressable>
      ) : <View style={{ width: 6 }} />}
      <View style={{ flex: 1 }}>
        <Text style={[t.font.h2, { color: t.c.text }]} numberOfLines={1}>{title}</Text>
        {subtitle ? <Text style={[t.font.small, { color: t.c.textDim }]} numberOfLines={1}>{subtitle}</Text> : null}
      </View>
      {right}
    </View>
  );
}

export function Title({ children, sub }: { children: React.ReactNode; sub?: string }) {
  const t = useTheme();
  return (
    <View style={{ marginTop: 8, marginBottom: 16 }}>
      <Text style={[t.font.title, { color: t.c.text }]}>{children}</Text>
      {sub ? <Text style={[t.font.body, { color: t.c.textDim, marginTop: 6, lineHeight: 21 }]}>{sub}</Text> : null}
    </View>
  );
}

type BtnKind = 'primary' | 'secondary' | 'ghost' | 'danger';
export function Button({ title, onPress, kind = 'primary', icon, loading, disabled, style }: {
  title: string; onPress?: () => void; kind?: BtnKind; icon?: IconName; loading?: boolean; disabled?: boolean; style?: StyleProp<ViewStyle>;
}) {
  const t = useTheme();
  const bg = { primary: t.c.primary, secondary: t.c.surface2, ghost: 'transparent', danger: t.c.dangerSoft }[kind];
  const fg = { primary: t.c.primaryText, secondary: t.c.text, ghost: t.c.primary, danger: t.c.danger }[kind];
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled || loading}
      accessibilityRole="button"
      style={({ pressed }) => [{
        backgroundColor: bg, borderRadius: t.radius.md, paddingVertical: 14, paddingHorizontal: 18, flexDirection: 'row',
        alignItems: 'center', justifyContent: 'center', opacity: disabled ? 0.45 : pressed ? 0.85 : 1,
        transform: [{ scale: pressed ? 0.985 : 1 }],
      }, style]}>
      {loading ? <ActivityIndicator color={fg} /> : (
        <>
          {icon ? <View style={{ marginRight: 8 }}><Icon name={icon} color={fg} size={19} /></View> : null}
          <Text style={[t.font.h3, { color: fg }]}>{title}</Text>
        </>
      )}
    </Pressable>
  );
}

export function Card({ children, style, onPress }: { children: React.ReactNode; style?: StyleProp<ViewStyle>; onPress?: () => void }) {
  const t = useTheme();
  const s = [{ backgroundColor: t.c.surface, borderRadius: t.radius.lg, padding: 16, borderWidth: StyleSheet.hairlineWidth, borderColor: t.c.border }, style];
  if (!onPress) return <View style={s}>{children}</View>;
  return <Pressable onPress={onPress} style={({ pressed }) => [s, { opacity: pressed ? 0.88 : 1 }]}>{children}</Pressable>;
}

export function SectionLabel({ children }: { children: React.ReactNode }) {
  const t = useTheme();
  return <Text style={[t.font.tiny, { color: t.c.textFaint, textTransform: 'uppercase', marginTop: 22, marginBottom: 8, marginLeft: 4 }]}>{children}</Text>;
}

export function Avatar({ id, name, size = 44, online }: { id: string; name: string; size?: number; online?: boolean }) {
  const t = useTheme();
  const initials = (name || '?').trim().split(/\s+/).map(w => w[0]).join('').slice(0, 2).toUpperCase();
  const col = avatarColor(id);
  return (
    <View style={{ width: size, height: size }}>
      <View style={{ width: size, height: size, borderRadius: size / 2, backgroundColor: col + '33', alignItems: 'center', justifyContent: 'center', borderWidth: 1.5, borderColor: col + '88' }}>
        <Text style={{ color: col, fontWeight: '700', fontSize: size * 0.38 }}>{initials}</Text>
      </View>
      {online !== undefined ? (
        <View style={{ position: 'absolute', right: -1, bottom: -1, width: size * 0.3, height: size * 0.3, borderRadius: size, backgroundColor: online ? t.c.success : t.c.textFaint, borderWidth: 2, borderColor: t.c.bg }} />
      ) : null}
    </View>
  );
}

export function Pill({ label, tone = 'neutral', icon }: { label: string; tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'primary'; icon?: IconName }) {
  const t = useTheme();
  const map = {
    neutral: [t.c.surface2, t.c.textDim], success: [t.c.successSoft, t.c.success], warning: [t.c.warningSoft, t.c.warning],
    danger: [t.c.dangerSoft, t.c.danger], primary: [t.c.primarySoft, t.c.primary],
  } as const;
  const [bg, fg] = map[tone];
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: bg, borderRadius: 999, paddingHorizontal: 9, paddingVertical: 4, alignSelf: 'flex-start' }}>
      {icon ? <View style={{ marginRight: 4 }}><Icon name={icon} size={12} color={fg} strokeWidth={2.5} /></View> : null}
      <Text style={{ color: fg, fontSize: 11.5, fontWeight: '600' }}>{label}</Text>
    </View>
  );
}

export function Row({ icon, title, subtitle, right, onPress, danger, last }: {
  icon?: IconName; title: string; subtitle?: string; right?: React.ReactNode; onPress?: () => void; danger?: boolean; last?: boolean;
}) {
  const t = useTheme();
  return (
    <Pressable onPress={onPress} disabled={!onPress} style={({ pressed }) => ({
      flexDirection: 'row', alignItems: 'center', paddingVertical: 13, paddingHorizontal: 14, backgroundColor: pressed ? t.c.surface2 : 'transparent',
      borderBottomWidth: last ? 0 : StyleSheet.hairlineWidth, borderBottomColor: t.c.border,
    })}>
      {icon ? (
        <View style={{ width: 34, height: 34, borderRadius: 10, backgroundColor: danger ? t.c.dangerSoft : t.c.primarySoft, alignItems: 'center', justifyContent: 'center', marginRight: 12 }}>
          <Icon name={icon} size={18} color={danger ? t.c.danger : t.c.primary} />
        </View>
      ) : null}
      <View style={{ flex: 1 }}>
        <Text style={[t.font.body, { color: danger ? t.c.danger : t.c.text, fontWeight: '500' }]}>{title}</Text>
        {subtitle ? <Text style={[t.font.small, { color: t.c.textDim, marginTop: 2 }]}>{subtitle}</Text> : null}
      </View>
      {right ?? (onPress ? <Icon name="chevron" size={18} color={t.c.textFaint} /> : null)}
    </Pressable>
  );
}

export function Group({ children }: { children: React.ReactNode }) {
  const t = useTheme();
  return <View style={{ backgroundColor: t.c.surface, borderRadius: t.radius.lg, overflow: 'hidden', borderWidth: StyleSheet.hairlineWidth, borderColor: t.c.border }}>{children}</View>;
}

export function ToggleRow({ title, subtitle, value, onChange, icon, last }: { title: string; subtitle?: string; value: boolean; onChange: (v: boolean) => void; icon?: IconName; last?: boolean }) {
  const t = useTheme();
  return <Row icon={icon} title={title} subtitle={subtitle} last={last}
    right={<Switch value={value} onValueChange={onChange} trackColor={{ true: t.c.primary, false: t.c.border }} thumbColor="#fff" />} />;
}

export function Input(props: TextInputProps) {
  const t = useTheme();
  return <TextInput placeholderTextColor={t.c.textFaint} {...props}
    style={[{ backgroundColor: t.c.surface, color: t.c.text, borderRadius: t.radius.md, paddingHorizontal: 16, paddingVertical: 14, fontSize: 16, borderWidth: 1, borderColor: t.c.border }, props.style]} />;
}

export function Empty({ icon, title, text, action }: { icon: IconName; title: string; text: string; action?: React.ReactNode }) {
  const t = useTheme();
  return (
    <View style={{ alignItems: 'center', paddingVertical: 48, paddingHorizontal: 24 }}>
      <View style={{ width: 72, height: 72, borderRadius: 36, backgroundColor: t.c.primarySoft, alignItems: 'center', justifyContent: 'center', marginBottom: 16 }}>
        <Icon name={icon} size={32} color={t.c.primary} />
      </View>
      <Text style={[t.font.h3, { color: t.c.text, marginBottom: 6, textAlign: 'center' }]}>{title}</Text>
      <Text style={[t.font.small, { color: t.c.textDim, textAlign: 'center', lineHeight: 19 }]}>{text}</Text>
      {action ? <View style={{ marginTop: 18, alignSelf: 'stretch' }}>{action}</View> : null}
    </View>
  );
}

export function Banner({ tone, icon, title, text, action }: { tone: 'warning' | 'danger' | 'success' | 'primary'; icon: IconName; title: string; text?: string; action?: React.ReactNode }) {
  const t = useTheme();
  const bg = { warning: t.c.warningSoft, danger: t.c.dangerSoft, success: t.c.successSoft, primary: t.c.primarySoft }[tone];
  const fg = { warning: t.c.warning, danger: t.c.danger, success: t.c.success, primary: t.c.primary }[tone];
  return (
    <View style={{ backgroundColor: bg, borderRadius: t.radius.md, padding: 14, flexDirection: 'row', marginBottom: 12 }}>
      <Icon name={icon} size={20} color={fg} />
      <View style={{ flex: 1, marginLeft: 10 }}>
        <Text style={[t.font.h3, { color: fg, fontSize: 14 }]}>{title}</Text>
        {text ? <Text style={[t.font.small, { color: t.c.textDim, marginTop: 3, lineHeight: 18 }]}>{text}</Text> : null}
        {action ? <View style={{ marginTop: 10 }}>{action}</View> : null}
      </View>
    </View>
  );
}

/** Soft pulsing rings: used on the Nearby screen while scanning. */
export function Pulse({ color, size = 140, active = true, children }: { color: string; size?: number; active?: boolean; children?: React.ReactNode }) {
  const a = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    if (!active) return;
    const loop = Animated.loop(Animated.timing(a, { toValue: 1, duration: 2200, useNativeDriver: true }));
    loop.start();
    return () => loop.stop();
  }, [active, a]);
  const ring = (delay: number) => {
    const v = Animated.modulo(Animated.add(a, delay), 1);
    return (
      <Animated.View style={{
        position: 'absolute', width: size, height: size, borderRadius: size / 2, borderWidth: 2, borderColor: color,
        opacity: active ? v.interpolate({ inputRange: [0, 1], outputRange: [0.55, 0] }) : 0,
        transform: [{ scale: v.interpolate({ inputRange: [0, 1], outputRange: [0.35, 1] }) }],
      }} />
    );
  };
  return (
    <View style={{ width: size, height: size, alignItems: 'center', justifyContent: 'center' }}>
      {ring(0)}{ring(0.33)}{ring(0.66)}
      {children}
    </View>
  );
}

export function FadeIn({ children, delay = 0, style }: { children: React.ReactNode; delay?: number; style?: StyleProp<ViewStyle> }) {
  const v = useRef(new Animated.Value(0)).current;
  useEffect(() => { Animated.timing(v, { toValue: 1, duration: 380, delay, useNativeDriver: true }).start(); }, [v, delay]);
  return <Animated.View style={[style, { opacity: v, transform: [{ translateY: v.interpolate({ inputRange: [0, 1], outputRange: [12, 0] }) }] }]}>{children}</Animated.View>;
}

export function Stat({ label, value, icon, tone }: { label: string; value: string | number; icon: IconName; tone?: string }) {
  const t = useTheme();
  return (
    <View style={{ flex: 1, backgroundColor: t.c.surface, borderRadius: t.radius.md, padding: 14, borderWidth: StyleSheet.hairlineWidth, borderColor: t.c.border }}>
      <Icon name={icon} size={18} color={tone ?? t.c.primary} />
      <Text style={{ color: t.c.text, fontSize: 22, fontWeight: '700', marginTop: 8 }}>{value}</Text>
      <Text style={[t.font.small, { color: t.c.textDim }]}>{label}</Text>
    </View>
  );
}

export const hairline = StyleSheet.hairlineWidth;
export type { Theme };

export function timeAgo(ts: number): string {
  if (!ts) return '';
  const s = Math.max(0, (Date.now() - ts) / 1000);
  if (s < 45) return 'just now';
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return new Date(ts).toLocaleDateString();
}

export function clock(ts: number): string {
  const d = new Date(ts);
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
}

export function bytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
