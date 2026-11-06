import React, { useRef, useState } from 'react';
import { Dimensions, FlatList, KeyboardAvoidingView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Banner, Button, Card, FadeIn, Input, Pill, Pulse, Screen, Title } from '../components/ui';
import { Icon, IconName } from '../components/Icon';
import { errorMessage, Mesh, requestNearbyPermissions, requestNotificationPermission } from '../native/mesh';
import { useMesh } from '../store/useMesh';
import { useTheme } from '../theme/theme';
import { create } from 'zustand';

/** True while the user is inside the first-run flow (so creating the identity doesn't skip setup). */
export const useOnboarding = create<{ active: boolean; set: (v: boolean) => void }>(set => ({ active: false, set: v => set({ active: v }) }));

export function SplashScreen() {
  const t = useTheme();
  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg, alignItems: 'center', justifyContent: 'center' }}>
      <Pulse color={t.c.primary} size={180}>
        <View style={{ width: 84, height: 84, borderRadius: 26, backgroundColor: t.c.primary, alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="mesh" size={44} color="#fff" />
        </View>
      </Pulse>
      <Text style={[t.font.h2, { color: t.c.text, marginTop: 18 }]}>Offline Mesh</Text>
      <Text style={[t.font.small, { color: t.c.textDim, marginTop: 6 }]}>Opening encrypted storage…</Text>
    </View>
  );
}

export function WelcomeScreen({ navigation }: any) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg, paddingTop: insets.top + 40, paddingHorizontal: 24, paddingBottom: insets.bottom + 24 }}>
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
        <FadeIn>
          <Pulse color={t.c.primary} size={220}>
            <View style={{ width: 96, height: 96, borderRadius: 30, backgroundColor: t.c.primary, alignItems: 'center', justifyContent: 'center' }}>
              <Icon name="mesh" size={52} color="#fff" />
            </View>
          </Pulse>
        </FadeIn>
        <FadeIn delay={150}>
          <Text style={{ color: t.c.text, fontSize: 34, fontWeight: '800', textAlign: 'center', marginTop: 24, letterSpacing: -0.8 }}>Offline Mesh</Text>
          <Text style={[t.font.body, { color: t.c.textDim, textAlign: 'center', marginTop: 10, lineHeight: 22 }]}>
            Private messages with people around you.{'\n'}No internet. No SIM. No servers. No sign-up.
          </Text>
        </FadeIn>
      </View>
      <FadeIn delay={300}>
        <Button title="Get started" onPress={() => navigation.navigate('Onboarding')} />
        <Text style={[t.font.tiny, { color: t.c.textFaint, textAlign: 'center', marginTop: 14 }]}>Everything stays on this phone</Text>
      </FadeIn>
    </View>
  );
}

const slides: { icon: IconName; title: string; text: string }[] = [
  { icon: 'bluetooth', title: 'Talk without internet', text: 'Phones near each other connect directly over Bluetooth. Turn off mobile data and Wi-Fi — it still works.' },
  { icon: 'lock', title: 'End-to-end encrypted', text: 'Messages are locked with the Signal Protocol on your phone before they leave it. Only the person you are talking to can read them.' },
  { icon: 'mesh', title: 'Phones help each other', text: 'If someone is out of range, another nearby phone can carry the sealed message closer. It cannot read it.' },
  { icon: 'alert', title: 'Honest limits', text: 'Bluetooth range is roughly 10–50 m and depends on walls and phones. Delivery only happens when a path between phones exists.' },
];

export function OnboardingScreen({ navigation }: any) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const [i, setI] = useState(0);
  const ref = useRef<FlatList>(null);
  const w = Dimensions.get('window').width;
  const next = () => (i < slides.length - 1 ? ref.current?.scrollToIndex({ index: i + 1 }) : navigation.navigate('CreateProfile'));
  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg, paddingTop: insets.top + 20, paddingBottom: insets.bottom + 24 }}>
      <FlatList ref={ref} data={slides} horizontal pagingEnabled showsHorizontalScrollIndicator={false} keyExtractor={s => s.title}
        onMomentumScrollEnd={e => setI(Math.round(e.nativeEvent.contentOffset.x / w))}
        renderItem={({ item }) => (
          <View style={{ width: w, paddingHorizontal: 32, justifyContent: 'center', alignItems: 'center' }}>
            <View style={{ width: 120, height: 120, borderRadius: 40, backgroundColor: t.c.primarySoft, alignItems: 'center', justifyContent: 'center', marginBottom: 32 }}>
              <Icon name={item.icon} size={56} color={t.c.primary} />
            </View>
            <Text style={[t.font.title, { color: t.c.text, textAlign: 'center' }]}>{item.title}</Text>
            <Text style={[t.font.body, { color: t.c.textDim, textAlign: 'center', marginTop: 12, lineHeight: 23 }]}>{item.text}</Text>
          </View>
        )} />
      <View style={{ flexDirection: 'row', justifyContent: 'center', marginBottom: 24 }}>
        {slides.map((_, k) => <View key={k} style={{ width: k === i ? 22 : 7, height: 7, borderRadius: 4, marginHorizontal: 3, backgroundColor: k === i ? t.c.primary : t.c.border }} />)}
      </View>
      <View style={{ paddingHorizontal: 24 }}>
        <Button title={i === slides.length - 1 ? 'Create my profile' : 'Next'} onPress={next} />
      </View>
    </View>
  );
}

export function CreateProfileScreen({ navigation }: any) {
  const t = useTheme();
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const setOnboarding = useOnboarding(s => s.set);
  const create = async () => {
    setBusy(true); setErr(null);
    try {
      setOnboarding(true);
      await Mesh.createIdentity(name);
      await useMesh.getState().refreshState();
      navigation.replace('SecuritySetup');
    } catch (e) { setErr(errorMessage(e)); setOnboarding(false); } finally { setBusy(false); }
  };
  return (
    <KeyboardAvoidingView style={{ flex: 1, backgroundColor: t.c.bg }} behavior="height">
      <Screen>
        <View style={{ height: 60 }} />
        <Title sub="This is the name nearby people will see after you connect. It is not your identity — your identity is a cryptographic key created on this phone.">
          Choose a name
        </Title>
        <Input value={name} onChangeText={setName} placeholder="e.g. Asha" maxLength={32} autoFocus autoCapitalize="words" returnKeyType="done" onSubmitEditing={create} />
        <Text style={[t.font.small, { color: t.c.textFaint, marginTop: 8 }]}>No phone number, email or account needed.</Text>
        {err ? <Text style={{ color: t.c.danger, marginTop: 12 }}>{err}</Text> : null}
        <Button title="Create secure identity" icon="key" onPress={create} loading={busy} disabled={!name.trim()} style={{ marginTop: 28 }} />
      </Screen>
    </KeyboardAvoidingView>
  );
}

export function SecuritySetupScreen() {
  const t = useTheme();
  const state = useMesh(s => s.state);
  const setOnboarding = useOnboarding(s => s.set);
  const [busy, setBusy] = useState(false);
  const [denied, setDenied] = useState<null | 'denied' | 'blocked'>(null);

  const allow = async () => {
    setBusy(true);
    try {
      const r = await requestNearbyPermissions();
      if (!r.granted) { setDenied(r.blocked ? 'blocked' : 'denied'); return; }
      await requestNotificationPermission();
      await Mesh.startService();
      await useMesh.getState().refreshState();
      setOnboarding(false);
    } catch { setDenied('denied'); } finally { setBusy(false); }
  };

  return (
    <Screen>
      <View style={{ height: 50 }} />
      <Title sub="Your keys were generated on this phone and never leave it.">Your identity is ready</Title>
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 12 }}>
          <Icon name="key" color={t.c.success} />
          <Text style={[t.font.h3, { color: t.c.text, marginLeft: 10, flex: 1 }]}>{state?.profile?.name}</Text>
          <Pill label={state?.hardwareBackedKeys ? 'Hardware-protected' : 'Software keystore'} tone={state?.hardwareBackedKeys ? 'success' : 'warning'} icon="lock" />
        </View>
        <Text style={[t.font.tiny, { color: t.c.textFaint }]}>SAFETY FINGERPRINT</Text>
        <Text style={[t.font.mono, { color: t.c.text, marginTop: 4, letterSpacing: 1 }]}>{state?.profile?.fingerprint}</Text>
      </Card>

      <Card style={{ marginTop: 14 }}>
        <Text style={[t.font.h3, { color: t.c.text, marginBottom: 10 }]}>Allow nearby access</Text>
        {[
          ['bluetooth', 'Nearby devices', 'Find, connect to and exchange encrypted data with phones running Offline Mesh.'],
          ['radar', 'Background connection', 'A notification stays visible while the app keeps discovering and relaying. You can switch it off any time.'],
          ['chat', 'Notifications (optional)', 'Tell you when a new message arrives. Message text is never shown.'],
        ].map(([icon, title, text]) => (
          <View key={title} style={{ flexDirection: 'row', marginBottom: 12 }}>
            <Icon name={icon as IconName} size={20} color={t.c.primary} />
            <View style={{ marginLeft: 12, flex: 1 }}>
              <Text style={[t.font.body, { color: t.c.text, fontWeight: '600' }]}>{title}</Text>
              <Text style={[t.font.small, { color: t.c.textDim, marginTop: 2, lineHeight: 18 }]}>{text}</Text>
            </View>
          </View>
        ))}
        {(state?.androidVersion ?? 31) < 31 ? (
          <Text style={[t.font.small, { color: t.c.textDim, marginBottom: 8 }]}>
            Android {state?.androidVersion} requires Location permission for Bluetooth scanning. Offline Mesh never reads or stores your location.
          </Text>
        ) : null}
      </Card>

      {denied ? (
        <View style={{ marginTop: 14 }}>
          <Banner tone="warning" icon="alert" title="Permission needed"
            text={denied === 'blocked' ? 'Nearby permission is blocked. Open settings and allow "Nearby devices".' : 'Without nearby permission the app cannot find other phones.'}
            action={denied === 'blocked' ? <Button kind="secondary" title="Open settings" onPress={() => Mesh.openAppSettings()} /> : undefined} />
        </View>
      ) : null}

      <Button title="Allow and start" icon="bluetooth" onPress={allow} loading={busy} style={{ marginTop: 22 }} />
      <Button title="Not now" kind="ghost" onPress={() => setOnboarding(false)} style={{ marginTop: 6 }} />
    </Screen>
  );
}
