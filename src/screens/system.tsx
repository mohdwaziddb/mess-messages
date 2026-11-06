import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import {
  Avatar, Banner, Button, bytes, Card, Empty, Group, Header, Input, Pill, Row, Screen, SectionLabel, Stat, timeAgo, ToggleRow,
} from '../components/ui';
import { Icon, IconName } from '../components/Icon';
import { errorMessage, Mesh, StorageInfo } from '../native/mesh';
import { stats, useMesh } from '../store/useMesh';
import { useTheme, useThemeMode } from '../theme/theme';
import { useServiceToggle } from './main';

const eventText: Record<string, [string, IconName, 'success' | 'warning' | 'danger' | 'primary']> = {
  identity_created: ['Identity created', 'key', 'success'],
  new_contact: ['New contact connected', 'user', 'primary'],
  contact_verified: ['Contact verified', 'shield', 'success'],
  contact_unverified: ['Verification removed', 'shield', 'warning'],
  identity_warning: ['Identity warning', 'alert', 'danger'],
  handshake_failed: ['Connection rejected', 'slash', 'warning'],
  undecryptable_packet: ['Unreadable packet dropped', 'alert', 'warning'],
  decrypt_failed: ['Message failed authentication', 'alert', 'danger'],
  malformed_content: ['Malformed message dropped', 'alert', 'warning'],
  wrong_channel: ['Message rejected', 'alert', 'warning'],
  group_message_rejected: ['Group message from non-member rejected', 'users', 'warning'],
  group_op_rejected: ['Unauthorized group change rejected', 'users', 'danger'],
  group_invite_rejected: ['Invalid group invite rejected', 'users', 'warning'],
  qr_mismatch: ['QR identity mismatch', 'alert', 'danger'],
  qr_replay_rejected: ['Reused QR code rejected', 'alert', 'warning'],
  contact_blocked: ['Contact blocked', 'slash', 'primary'],
  contact_unblocked: ['Contact unblocked', 'user', 'primary'],
  device_revoked: ['Device keys revoked', 'key', 'warning'],
  attachment_integrity: ['Corrupted file discarded', 'file', 'danger'],
};

export function SecurityCenterScreen({ navigation }: any) {
  const t = useTheme();
  const { state, contacts, security, refreshSecurity, refreshLists } = useMesh();
  useFocusEffect(useCallback(() => { refreshSecurity(); refreshLists(); }, [refreshSecurity, refreshLists]));
  const verified = contacts.filter(c => c.verified);
  const unverified = contacts.filter(c => !c.verified && !c.blocked);
  const warnings = contacts.filter(c => c.identityWarning);
  const lastVerified = Math.max(0, ...verified.map(c => c.verifiedAt ?? 0));

  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Header title="Security" back={false} />
      <Screen>
        <Card style={{ alignItems: 'center', paddingVertical: 22 }}>
          <View style={{ width: 76, height: 76, borderRadius: 38, backgroundColor: warnings.length ? t.c.warningSoft : t.c.successSoft, alignItems: 'center', justifyContent: 'center' }}>
            <Icon name={warnings.length ? 'alert' : 'shield'} size={38} color={warnings.length ? t.c.warning : t.c.success} />
          </View>
          <Text style={[t.font.h2, { color: t.c.text, marginTop: 12 }]}>{warnings.length ? 'Review needed' : 'Encryption active'}</Text>
          <Text style={[t.font.small, { color: t.c.textDim, marginTop: 6, textAlign: 'center', lineHeight: 19 }]}>
            Every message is end-to-end encrypted with the Signal Protocol (Double Ratchet, X25519 + post-quantum Kyber key agreement). There is no unencrypted mode.
          </Text>
        </Card>

        {warnings.map(w => (
          <Banner key={w.id} tone="danger" icon="alert" title={`Identity warning: ${w.name}`} text="Same name as another contact with a different key. Verify in person."
            action={<Button kind="secondary" title="Review" onPress={() => navigation.navigate('UserProfile', { peerId: w.id })} />} />
        ))}

        <View style={{ flexDirection: 'row', gap: 10, marginTop: 14 }}>
          <Stat label="Verified" value={verified.length} icon="shield" tone={t.c.success} />
          <Stat label="Unverified" value={unverified.length} icon="alert" tone={unverified.length ? t.c.warning : t.c.textDim} />
        </View>

        <SectionLabel>This device</SectionLabel>
        <Card>
          <Text style={[t.font.tiny, { color: t.c.textFaint }]}>YOUR IDENTITY FINGERPRINT</Text>
          <Text style={[t.font.mono, { color: t.c.text, marginTop: 4, letterSpacing: 1 }]}>{state?.profile?.fingerprint}</Text>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 12 }}>
            <Pill label={state?.hardwareBackedKeys ? 'Keys in secure hardware' : 'Software keystore'} tone={state?.hardwareBackedKeys ? 'success' : 'warning'} icon="key" />
            <Pill label="Database encrypted (SQLCipher)" tone="success" icon="database" />
            <Pill label="No cloud backup" tone="success" icon="lock" />
          </View>
          <Text style={[t.font.small, { color: t.c.textDim, marginTop: 10, lineHeight: 19 }]}>
            Signed pre-keys are rotated with the identity; session keys change with every message (forward secrecy). Last verification: {lastVerified ? timeAgo(lastVerified) : 'never'}.
          </Text>
        </Card>

        <SectionLabel>Manage</SectionLabel>
        <Group>
          <Row icon="shield" title="Verified devices" subtitle={`${verified.length} verified · ${unverified.length} not verified`} onPress={() => navigation.navigate('VerifiedDevices')} />
          <Row icon="qr" title="Show my QR code" onPress={() => navigation.navigate('MyQr')} />
          <Row icon="scan" title="Verify someone by QR" onPress={() => navigation.navigate('QrScanner')} />
          <Row icon="eye" title="Privacy settings" last onPress={() => navigation.navigate('Privacy')} />
        </Group>

        <SectionLabel>Security log</SectionLabel>
        {security.length === 0 ? <Text style={[t.font.small, { color: t.c.textDim, marginLeft: 4 }]}>No events yet.</Text> : (
          <Group>
            {security.slice(0, 30).map((e, i) => {
              const [label, icon, tone] = eventText[e.kind] ?? [e.kind, 'help', 'primary'];
              const col = { success: t.c.success, warning: t.c.warning, danger: t.c.danger, primary: t.c.primary }[tone];
              const who = contacts.find(c => c.id === e.contactId)?.name;
              return (
                <View key={e.id} style={{ flexDirection: 'row', padding: 12, borderBottomWidth: i === Math.min(security.length, 30) - 1 ? 0 : 1, borderBottomColor: t.c.border }}>
                  <Icon name={icon} size={18} color={col} />
                  <View style={{ flex: 1, marginLeft: 10 }}>
                    <Text style={[t.font.body, { color: t.c.text }]}>{label}{who ? ` · ${who}` : ''}</Text>
                    {e.detail ? <Text style={[t.font.small, { color: t.c.textDim, marginTop: 2 }]}>{e.detail}</Text> : null}
                  </View>
                  <Text style={[t.font.tiny, { color: t.c.textFaint }]}>{timeAgo(e.at)}</Text>
                </View>
              );
            })}
          </Group>
        )}
      </Screen>
    </View>
  );
}

export function VerifiedDevicesScreen({ navigation }: any) {
  const t = useTheme();
  const contacts = useMesh(s => s.contacts);
  const sorted = [...contacts].sort((a, b) => Number(b.verified) - Number(a.verified));
  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Header title="Verified devices" subtitle="Each contact = one device key" />
      <Screen>
        {sorted.length === 0 ? <Empty icon="shield" title="No contacts yet" text="People you connect with appear here. Verify them by QR or safety number." /> : (
          <Group>
            {sorted.map((c, i) => (
              <Row key={c.id} title={c.name} subtitle={`${c.fingerprint.slice(0, 19)}… · ${c.verified ? `verified ${c.verifiedAt ? timeAgo(c.verifiedAt) : ''}` : 'not verified'}`}
                last={i === sorted.length - 1} onPress={() => navigation.navigate('UserProfile', { peerId: c.id })}
                right={c.blocked ? <Pill label="Blocked" tone="danger" /> : c.verified ? <Pill label="Verified" tone="success" icon="shield" /> : <Pill label="Verify" tone="warning" />} />
            ))}
          </Group>
        )}
      </Screen>
    </View>
  );
}

export function MeshDashboardScreen() {
  const t = useTheme();
  const { state, nearby, refreshState } = useMesh();
  const s = stats(state);
  useFocusEffect(useCallback(() => { refreshState(); const i = setInterval(refreshState, 3000); return () => clearInterval(i); }, [refreshState]));
  const toggleRelay = (v: boolean) => Mesh.setSetting('relayEnabled', v).then(refreshState);
  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Header title="Mesh network" subtitle="Multi-hop store & forward" />
      <Screen>
        <View style={{ flexDirection: 'row', gap: 10 }}>
          <Stat label="Direct links" value={nearby?.links ?? 0} icon="bluetooth" />
          <Stat label="Packets relayed" value={s.relayed} icon="mesh" />
        </View>
        <View style={{ flexDirection: 'row', gap: 10, marginTop: 10 }}>
          <Stat label="Carrying for others" value={s.relayQueue} icon="database" />
          <Stat label="My messages waiting" value={s.pending} icon="clock" tone={s.pending ? t.c.warning : undefined} />
        </View>

        <SectionLabel>Relay</SectionLabel>
        <Group>
          <ToggleRow icon="mesh" title="Help relay messages" subtitle="Carry sealed messages for others. You can never read them." value={!!state?.relayEnabled} onChange={toggleRelay} last />
        </Group>
        <Text style={[t.font.small, { color: t.c.textDim, marginTop: 10, lineHeight: 19, marginHorizontal: 4 }]}>
          Relay pauses automatically below 20% battery (unless charging). Limits: 120 packets/min per neighbour, 500 packets / 16 MB stored, kept at most 24 h, max 6 hops.
        </Text>

        <SectionLabel>How it works</SectionLabel>
        <Card>
          {[
            ['A → B → C', 'If C is out of A\'s range, B forwards the sealed packet. B only sees a random-looking routing tag.'],
            ['Store & forward', 'If no path exists now, phones hold packets and hand them on when they meet someone new.'],
            ['Receipts', 'You only see "Delivered" after an encrypted receipt comes back from the recipient.'],
            ['Limits', 'Delivery is not guaranteed: it needs phones with the app to be in range of each other at some point before the message expires (7 days).'],
          ].map(([h, d]) => (
            <View key={h} style={{ marginBottom: 12 }}>
              <Text style={[t.font.h3, { color: t.c.text }]}>{h}</Text>
              <Text style={[t.font.small, { color: t.c.textDim, marginTop: 3, lineHeight: 19 }]}>{d}</Text>
            </View>
          ))}
        </Card>

        <SectionLabel>Radio capabilities</SectionLabel>
        <Group>
          <Row icon="bluetooth" title="Bluetooth LE" subtitle={state?.bleSupported ? (state.canAdvertise ? 'Scan + advertise supported · in use' : 'Scan only — this phone cannot advertise') : 'Not supported'} />
          <Row icon="wifi" title="Wi-Fi Direct" subtitle={state?.wifiDirectSupported ? 'Supported by this phone · not used in this beta' : 'Not supported'} />
          <Row icon="wifi" title="Wi-Fi Aware" subtitle={state?.wifiAwareSupported ? 'Supported by this phone · not used in this beta' : 'Not supported'} last />
        </Group>
      </Screen>
    </View>
  );
}

export function StorageScreen() {
  const t = useTheme();
  const [info, setInfo] = useState<StorageInfo | null>(null);
  const load = useCallback(() => { Mesh.storageInfo().then(setInfo).catch(() => {}); }, []);
  useEffect(() => { load(); }, [load]);
  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Header title="Storage" subtitle="Everything is encrypted at rest" />
      <Screen>
        <Group>
          <Row icon="chat" title="Messages" right={<Text style={{ color: t.c.textDim }}>{info?.messages ?? '–'}</Text>} />
          <Row icon="file" title="Files" right={<Text style={{ color: t.c.textDim }}>{info ? `${info.attachments} · ${bytes(info.encryptedFileBytes)}` : '–'}</Text>} />
          <Row icon="clock" title="Outgoing queue" right={<Text style={{ color: t.c.textDim }}>{info?.outbox ?? '–'}</Text>} />
          <Row icon="mesh" title="Relay queue (others' packets)" right={<Text style={{ color: t.c.textDim }}>{info ? `${info.relayQueue} · ${bytes(info.relayBytes)}` : '–'}</Text>} last />
        </Group>
        <Text style={[t.font.small, { color: t.c.textDim, marginTop: 10, marginHorizontal: 4, lineHeight: 19 }]}>
          The database is encrypted with SQLCipher (AES-256). Files are encrypted with AES-256-GCM. Both keys are wrapped by the Android Keystore and excluded from backups.
        </Text>
        <SectionLabel>Clean up</SectionLabel>
        <Group>
          <Row icon="mesh" title="Clear relay queue" subtitle="Drop sealed packets you are carrying for others" onPress={() => Mesh.clearRelayQueue().then(load)} last />
        </Group>
        <SectionLabel>Danger zone</SectionLabel>
        <Group>
          <Row icon="trash" danger title="Erase everything" subtitle="Deletes identity, keys, contacts, messages and files from this phone" last
            onPress={() => Alert.alert('Erase everything?', 'This permanently deletes your identity and all messages. Contacts will see you as a new person. This cannot be undone.', [
              { text: 'Cancel' }, { text: 'Erase', style: 'destructive', onPress: () => Mesh.wipeAllData() },
            ])} />
        </Group>
      </Screen>
    </View>
  );
}

export function PrivacyScreen() {
  const t = useTheme();
  const { state, refreshState } = useMesh();
  const set = (k: 'visible' | 'autoConnect' | 'readReceipts', v: boolean) => Mesh.setSetting(k, v).then(refreshState).catch(e => Alert.alert('Settings', errorMessage(e)));
  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Header title="Privacy" />
      <Screen>
        <Group>
          <ToggleRow icon="eye" title="Visible to nearby people" subtitle="Advertise a rotating anonymous Bluetooth id" value={!!state?.visible} onChange={v => set('visible', v)} />
          <ToggleRow icon="radar" title="Auto-connect" subtitle="Securely connect to nearby Offline Mesh phones automatically" value={!!state?.autoConnect} onChange={v => set('autoConnect', v)} />
          <ToggleRow icon="checks" title="Read receipts" subtitle="Let senders know when you've read their message" value={!!state?.readReceipts} onChange={v => set('readReceipts', v)} last />
        </Group>
        <SectionLabel>What others can see</SectionLabel>
        <Card>
          {[
            ['Anyone nearby (not connected)', 'Only that an Offline Mesh phone is nearby, plus a random id that changes every 15 minutes. No name, number or key.'],
            ['Phones you connect to', 'Your public key and, inside the encrypted channel, your chosen name.'],
            ['Relay phones', 'An opaque routing tag, packet size and timing. Never the content or the sender.'],
          ].map(([h, d]) => (
            <View key={h} style={{ marginBottom: 12 }}>
              <Text style={[t.font.h3, { color: t.c.text }]}>{h}</Text>
              <Text style={[t.font.small, { color: t.c.textDim, marginTop: 3, lineHeight: 19 }]}>{d}</Text>
            </View>
          ))}
        </Card>
      </Screen>
    </View>
  );
}

export function SettingsScreen({ navigation }: any) {
  const t = useTheme();
  const { state, refreshState } = useMesh();
  const svc = useServiceToggle();
  const { mode, setMode } = useThemeMode();
  const [editing, setEditing] = useState<string | null>(null);
  const saveName = async () => {
    try { await Mesh.setMyName(editing ?? ''); setEditing(null); refreshState(); } catch (e) { Alert.alert('Name', errorMessage(e)); }
  };
  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Header title="Settings" back={false} />
      <Screen>
        <Card style={{ flexDirection: 'row', alignItems: 'center' }}>
          <Avatar id={state?.profile?.id ?? ''} name={state?.profile?.name ?? ''} size={56} />
          <View style={{ flex: 1, marginLeft: 12 }}>
            {editing !== null ? (
              <>
                <Input value={editing} onChangeText={setEditing} maxLength={32} autoFocus style={{ paddingVertical: 8 }} />
                <View style={{ flexDirection: 'row', gap: 8, marginTop: 8 }}>
                  <Button title="Save" onPress={saveName} style={{ flex: 1, paddingVertical: 9 }} />
                  <Button title="Cancel" kind="secondary" onPress={() => setEditing(null)} style={{ flex: 1, paddingVertical: 9 }} />
                </View>
              </>
            ) : (
              <>
                <Text style={[t.font.h2, { color: t.c.text }]}>{state?.profile?.name}</Text>
                <Text style={[t.font.small, { color: t.c.textDim }]} numberOfLines={1}>{state?.profile?.fingerprint}</Text>
              </>
            )}
          </View>
          {editing === null ? <Button kind="ghost" title="Edit" onPress={() => setEditing(state?.profile?.name ?? '')} /> : null}
        </Card>

        <SectionLabel>Connection</SectionLabel>
        <Group>
          <ToggleRow icon="radar" title="Nearby discovery & background service" subtitle={svc.running ? 'Running · notification visible' : 'Stopped'} value={svc.running} onChange={v => (v ? svc.start() : svc.stop())} />
          <Row icon="mesh" title="Mesh network" subtitle={state?.relayEnabled ? 'Relay on' : 'Relay off'} onPress={() => navigation.navigate('MeshDashboard')} />
          <Row icon="help" title="Connection troubleshooting" onPress={() => navigation.navigate('Troubleshooting')} last />
        </Group>

        <SectionLabel>Appearance</SectionLabel>
        <Group>
          {(['system', 'dark', 'light'] as const).map((m, i) => (
            <Row key={m} title={{ system: 'Match system', dark: 'Dark', light: 'Light' }[m]} onPress={() => setMode(m)} last={i === 2}
              right={mode === m ? <Icon name="check" color={t.c.primary} /> : <View />} />
          ))}
        </Group>

        <SectionLabel>Privacy & data</SectionLabel>
        <Group>
          <Row icon="eye" title="Privacy" onPress={() => navigation.navigate('Privacy')} />
          <Row icon="shield" title="Security center" onPress={() => navigation.navigate('Tabs', { screen: 'Security' })} />
          <Row icon="database" title="Storage" onPress={() => navigation.navigate('Storage')} last />
        </Group>

        <Text style={[t.font.tiny, { color: t.c.textFaint, textAlign: 'center', marginTop: 24 }]}>
          Offline Mesh 0.1.0 beta · Signal Protocol via libsignal (AGPL-3.0){'\n'}Android {state?.androidVersion} · {state?.manufacturer}
        </Text>
      </Screen>
    </View>
  );
}

export function TroubleshootingScreen() {
  const t = useTheme();
  const { state, nearby, refreshState } = useMesh();
  const svc = useServiceToggle();
  useEffect(() => { refreshState(); }, [refreshState]);
  const checks: [string, boolean, string, (() => void) | undefined][] = [
    ['Bluetooth LE supported', !!state?.bleSupported, 'This phone has no Bluetooth LE. Offline Mesh cannot work here.', undefined],
    ['Bluetooth is on', !!state?.bleEnabled, 'Turn on Bluetooth (Airplane mode can stay on).', () => Mesh.openBluetoothSettings()],
    ['Nearby permission granted', !!state?.permissionsGranted, 'Allow "Nearby devices" for Offline Mesh.', () => Mesh.openAppSettings()],
    ['Discovery service running', !!state?.serviceRunning, 'Start nearby discovery.', svc.start],
    ['Can advertise (be found)', !!state?.canAdvertise, 'This phone can scan but not advertise: others must find it from their side, or connect via QR from this phone.', undefined],
    ['Battery optimisation off', !state?.batteryOptimized, 'Some phones stop background Bluetooth. Exclude Offline Mesh from battery optimisation.', () => Mesh.openBatterySettings()],
  ];
  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Header title="Troubleshooting" subtitle={`${state?.manufacturer ?? ''} · Android ${state?.androidVersion ?? ''}`} />
      <Screen>
        <Group>
          {checks.map(([label, ok, fix, action], i) => (
            <Row key={label} icon={ok ? 'check' : 'alert'} title={label} subtitle={ok ? undefined : fix} danger={!ok} last={i === checks.length - 1}
              onPress={!ok && action ? action : undefined} right={ok ? <Pill label="OK" tone="success" /> : undefined} />
          ))}
        </Group>
        <SectionLabel>Live</SectionLabel>
        <Card>
          <Text style={[t.font.body, { color: t.c.text }]}>Nearby devices seen: {nearby?.devices.length ?? 0}</Text>
          <Text style={[t.font.body, { color: t.c.text, marginTop: 4 }]}>Secure links: {nearby?.links ?? 0}</Text>
        </Card>
        <SectionLabel>Tips</SectionLabel>
        <Card>
          {[
            'Keep both phones within about 10 m for the first connection; walls and bodies reduce range a lot.',
            'Both phones need the app open (or the background service running) with discovery on.',
            'Xiaomi, Oppo, Vivo, Realme, OnePlus, Samsung: allow "Autostart" / "Unrestricted battery" for Offline Mesh, or background discovery may stop.',
            'If a connection is stuck, turn Bluetooth off and on, then reopen the app.',
            'Use "Scan QR" to connect directly to a specific person.',
          ].map(x => <Text key={x} style={[t.font.small, { color: t.c.textDim, marginBottom: 8, lineHeight: 19 }]}>• {x}</Text>)}
        </Card>
      </Screen>
    </View>
  );
}
