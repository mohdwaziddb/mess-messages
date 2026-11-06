import React, { useState } from 'react';
import { Alert, FlatList, Pressable, RefreshControl, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useFocusEffect } from '@react-navigation/native';
import { Avatar, Banner, Button, Card, Empty, Header, Pill, Pulse, Screen, SectionLabel, Stat, timeAgo } from '../components/ui';
import { Icon, IconName } from '../components/Icon';
import { errorMessage, Mesh, requestNearbyPermissions } from '../native/mesh';
import { stats, useMesh } from '../store/useMesh';
import { useTheme } from '../theme/theme';

export function useServiceToggle() {
  const state = useMesh(s => s.state);
  const [busy, setBusy] = useState(false);
  const start = async () => {
    setBusy(true);
    try {
      if (!state?.permissionsGranted) {
        const r = await requestNearbyPermissions();
        if (!r.granted) {
          Alert.alert('Permission needed', 'Allow "Nearby devices" so Offline Mesh can find phones around you.', [
            { text: 'Cancel' }, { text: 'Open settings', onPress: () => Mesh.openAppSettings() },
          ]);
          return;
        }
      }
      if (!state?.bleEnabled) {
        Alert.alert('Bluetooth is off', 'Turn on Bluetooth to discover nearby people.', [{ text: 'Cancel' }, { text: 'Bluetooth settings', onPress: () => Mesh.openBluetoothSettings() }]);
        return;
      }
      await Mesh.startService();
    } catch (e) { Alert.alert('Could not start', errorMessage(e)); } finally {
      setBusy(false);
      setTimeout(() => useMesh.getState().refreshState(), 600);
    }
  };
  const stop = async () => { await Mesh.stopService(); setTimeout(() => useMesh.getState().refreshState(), 400); };
  return { start, stop, busy, running: !!state?.serviceRunning };
}

function QuickAction({ icon, label, onPress }: { icon: IconName; label: string; onPress: () => void }) {
  const t = useTheme();
  return (
    <Pressable onPress={onPress} style={({ pressed }) => ({ flex: 1, alignItems: 'center', opacity: pressed ? 0.7 : 1 })}>
      <View style={{ width: 56, height: 56, borderRadius: 18, backgroundColor: t.c.primarySoft, alignItems: 'center', justifyContent: 'center' }}>
        <Icon name={icon} color={t.c.primary} size={24} />
      </View>
      <Text style={[t.font.small, { color: t.c.text, marginTop: 7, fontWeight: '500' }]}>{label}</Text>
    </Pressable>
  );
}

export function HomeScreen({ navigation }: any) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const { state, nearby, invites, refreshState, refreshNearby, refreshLists } = useMesh();
  const s = stats(state);
  const svc = useServiceToggle();
  const nearbyCount = (nearby?.people.length ?? 0) + (nearby?.devices.length ?? 0);
  useFocusEffect(React.useCallback(() => { refreshState(); refreshNearby(); refreshLists(); }, [refreshState, refreshNearby, refreshLists]));

  const network = !state?.bleEnabled ? ['Bluetooth off', 'danger'] : svc.running ? ['Active', 'success'] : ['Paused', 'warning'];
  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Screen>
        <View style={{ paddingTop: insets.top + 12, flexDirection: 'row', alignItems: 'center', marginBottom: 18 }}>
          <Pressable onPress={() => navigation.navigate('MyQr')}><Avatar id={state?.profile?.id ?? ''} name={state?.profile?.name ?? ''} size={48} /></Pressable>
          <View style={{ flex: 1, marginLeft: 12 }}>
            <Text style={[t.font.small, { color: t.c.textDim }]}>Offline Mesh</Text>
            <Text style={[t.font.h2, { color: t.c.text }]} numberOfLines={1}>Hi, {state?.profile?.name}</Text>
          </View>
          <Pill label={network[0]} tone={network[1] as any} icon={svc.running ? 'radar' : 'alert'} />
        </View>

        {!svc.running ? (
          <Banner tone="warning" icon="radar" title="Nearby discovery is off"
            text="Turn it on to find people and deliver waiting messages."
            action={<Button title="Turn on" icon="bluetooth" onPress={svc.start} loading={svc.busy} />} />
        ) : null}
        {invites.length > 0 ? (
          <Banner tone="primary" icon="users" title={`${invites.length} group invitation${invites.length > 1 ? 's' : ''}`}
            text={invites.map(i => `${i.name} (from ${i.from || 'unknown'})`).join(', ')}
            action={<Button kind="secondary" title="Review" onPress={() => navigation.navigate('Chats')} />} />
        ) : null}

        <Card onPress={() => navigation.navigate('Nearby')} style={{ flexDirection: 'row', alignItems: 'center' }}>
          <Pulse color={t.c.primary} size={64} active={svc.running}>
            <Icon name="radar" color={t.c.primary} size={26} />
          </Pulse>
          <View style={{ flex: 1, marginLeft: 12 }}>
            <Text style={[t.font.h3, { color: t.c.text }]}>{svc.running ? `${nearbyCount} nearby` : 'Not scanning'}</Text>
            <Text style={[t.font.small, { color: t.c.textDim, marginTop: 2 }]}>
              {svc.running ? `${s.connected} securely connected · ${nearby?.visible ? 'you are visible' : 'you are hidden'}` : 'Discovery paused'}
            </Text>
          </View>
          <Icon name="chevron" color={t.c.textFaint} />
        </Card>

        <View style={{ flexDirection: 'row', marginTop: 20 }}>
          <QuickAction icon="radar" label="Find nearby" onPress={() => navigation.navigate('Nearby')} />
          <QuickAction icon="scan" label="Scan QR" onPress={() => navigation.navigate('QrScanner')} />
          <QuickAction icon="qr" label="My QR" onPress={() => navigation.navigate('MyQr')} />
          <QuickAction icon="users" label="New group" onPress={() => navigation.navigate('CreateGroup')} />
        </View>

        <SectionLabel>Overview</SectionLabel>
        <View style={{ flexDirection: 'row', gap: 10 }}>
          <Stat label="Connected" value={s.connected} icon="bluetooth" />
          <Stat label="Contacts" value={s.contacts} icon="user" />
        </View>
        <View style={{ flexDirection: 'row', gap: 10, marginTop: 10 }}>
          <Stat label="Waiting to send" value={s.pending} icon="clock" tone={s.pending ? t.c.warning : undefined} />
          <Stat label="Groups" value={s.groups} icon="users" />
        </View>

        <SectionLabel>Status</SectionLabel>
        <Card onPress={() => navigation.navigate('SecurityCenter')}>
          {[
            ['lock', 'Encryption', 'Signal Protocol · always on', t.c.success],
            ['shield', 'Verified contacts', `${s.verified} of ${s.contacts}`, s.contacts && s.verified < s.contacts ? t.c.warning : t.c.success],
            ['mesh', 'Mesh relay', s.relayEnabled ? `On · ${s.relayed} packets forwarded` : 'Off', s.relayEnabled ? t.c.success : t.c.textDim],
            ['key', 'Key storage', state?.hardwareBackedKeys ? 'Hardware-backed Android Keystore' : 'Android Keystore (software)', state?.hardwareBackedKeys ? t.c.success : t.c.warning],
          ].map(([icon, title, value, color]) => (
            <View key={title as string} style={{ flexDirection: 'row', alignItems: 'center', paddingVertical: 8 }}>
              <Icon name={icon as IconName} size={18} color={color as string} />
              <Text style={[t.font.body, { color: t.c.text, marginLeft: 10, flex: 1 }]}>{title as string}</Text>
              <Text style={[t.font.small, { color: t.c.textDim }]}>{value as string}</Text>
            </View>
          ))}
        </Card>
      </Screen>
    </View>
  );
}

export function ChatsScreen({ navigation }: any) {
  const t = useTheme();
  const { conversations, invites, refreshLists } = useMesh();
  const [refreshing, setRefreshing] = useState(false);
  useFocusEffect(React.useCallback(() => { refreshLists(); }, [refreshLists]));
  const open = (c: any) => navigation.navigate(c.kind === 'group' ? 'GroupChat' : 'Chat', { convId: c.id, title: c.title, peerId: c.peerId, groupId: c.groupId });

  const inviteAction = async (groupId: string, accept: boolean) => {
    try {
      if (accept) { const conv = await Mesh.acceptInvite(groupId); refreshLists(); navigation.navigate('GroupChat', { convId: conv, groupId }); }
      else { await Mesh.rejectInvite(groupId); refreshLists(); }
    } catch (e) { Alert.alert('Invitation', errorMessage(e)); }
  };

  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Header title="Chats" back={false} right={
        <Pressable onPress={() => navigation.navigate('CreateGroup')} hitSlop={10} style={{ padding: 6 }}><Icon name="users" color={t.c.primary} /></Pressable>
      } />
      <FlatList
        data={conversations}
        keyExtractor={c => c.id}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={async () => { setRefreshing(true); await refreshLists(); setRefreshing(false); }} />}
        ListHeaderComponent={invites.length ? (
          <View style={{ paddingHorizontal: 16 }}>
            {invites.map(i => (
              <Card key={i.groupId} style={{ marginBottom: 10 }}>
                <Text style={[t.font.h3, { color: t.c.text }]}>Invitation: {i.name}</Text>
                <Text style={[t.font.small, { color: t.c.textDim, marginTop: 4 }]}>From {i.from || 'a contact'} · {i.memberCount} members · signed by group admin</Text>
                <View style={{ flexDirection: 'row', gap: 10, marginTop: 12 }}>
                  <Button title="Join" style={{ flex: 1 }} onPress={() => inviteAction(i.groupId, true)} />
                  <Button title="Decline" kind="secondary" style={{ flex: 1 }} onPress={() => inviteAction(i.groupId, false)} />
                </View>
              </Card>
            ))}
          </View>
        ) : undefined}
        ListEmptyComponent={<Empty icon="chat" title="No conversations yet" text="Find someone nearby or scan their QR code to start a secure chat."
          action={<Button title="Find nearby" icon="radar" onPress={() => navigation.navigate('Nearby')} />} />}
        renderItem={({ item: c }) => (
          <Pressable onPress={() => open(c)} style={({ pressed }) => ({ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 12, backgroundColor: pressed ? t.c.surface : 'transparent' })}>
            {c.kind === 'group' ? (
              <View style={{ width: 50, height: 50, borderRadius: 16, backgroundColor: t.c.accentSoft, alignItems: 'center', justifyContent: 'center' }}>
                <Icon name="users" color={t.c.accent} />
              </View>
            ) : <Avatar id={c.peerId ?? c.id} name={c.title} size={50} online={c.connected} />}
            <View style={{ flex: 1, marginLeft: 12 }}>
              <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                <Text style={[t.font.h3, { color: t.c.text, flexShrink: 1 }]} numberOfLines={1}>{c.title}</Text>
                {c.kind === 'direct' && c.verified ? <View style={{ marginLeft: 5 }}><Icon name="shield" size={14} color={t.c.success} /></View> : null}
                {c.identityWarning ? <View style={{ marginLeft: 5 }}><Icon name="alert" size={14} color={t.c.warning} /></View> : null}
                <Text style={[t.font.tiny, { color: t.c.textFaint, marginLeft: 'auto' }]}>{c.lastMsgAt ? timeAgo(c.lastMsgAt) : ''}</Text>
              </View>
              <View style={{ flexDirection: 'row', alignItems: 'center', marginTop: 3 }}>
                <Text style={[t.font.small, { color: c.unread ? t.c.text : t.c.textDim, flex: 1 }]} numberOfLines={1}>
                  {!c.active ? 'You are no longer a member' : c.preview || 'Encrypted chat ready'}
                </Text>
                {c.unread ? <View style={{ minWidth: 20, height: 20, borderRadius: 10, backgroundColor: t.c.primary, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 6 }}>
                  <Text style={{ color: '#fff', fontSize: 11, fontWeight: '700' }}>{c.unread}</Text>
                </View> : null}
              </View>
            </View>
          </Pressable>
        )}
      />
    </View>
  );
}

function signal(rssi: number) {
  if (rssi >= -60) return ['Excellent', 'success'] as const;
  if (rssi >= -75) return ['Good', 'success'] as const;
  if (rssi >= -88) return ['Fair', 'warning'] as const;
  return ['Weak', 'danger'] as const;
}

export function NearbyScreen({ navigation }: any) {
  const t = useTheme();
  const { nearby, state, refreshNearby } = useMesh();
  const svc = useServiceToggle();
  useFocusEffect(React.useCallback(() => { refreshNearby(); const i = setInterval(refreshNearby, 2000); return () => clearInterval(i); }, [refreshNearby]));

  const chat = async (peerId: string, name: string) => {
    try { const conv = await Mesh.openDirect(peerId); navigation.navigate('Chat', { convId: conv, title: name, peerId }); }
    catch (e) { Alert.alert('Chat', errorMessage(e)); }
  };

  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Header title="Nearby" subtitle={svc.running ? 'Scanning with Bluetooth' : 'Discovery is off'} back={false} right={
        <Pressable onPress={() => navigation.navigate('QrScanner')} hitSlop={10} style={{ padding: 6 }}><Icon name="scan" color={t.c.primary} /></Pressable>
      } />
      <Screen>
        <View style={{ alignItems: 'center', marginVertical: 12 }}>
          <Pulse color={svc.running ? t.c.primary : t.c.textFaint} size={170} active={svc.running}>
            <Avatar id={state?.profile?.id ?? ''} name={state?.profile?.name ?? ''} size={64} />
          </Pulse>
          <Text style={[t.font.small, { color: t.c.textDim, marginTop: 6, textAlign: 'center' }]}>
            {svc.running ? (nearby?.visible ? 'Others nearby can find you. Your name is only shared after a secure connection.' : 'You are hidden. You can still find others.') : 'Turn on discovery to find people around you.'}
          </Text>
          {!svc.running ? <Button title="Start discovery" icon="radar" onPress={svc.start} loading={svc.busy} style={{ marginTop: 14, alignSelf: 'stretch' }} /> : null}
        </View>

        {svc.running ? (
          <>
            <SectionLabel>Connected securely · {nearby?.people.length ?? 0}</SectionLabel>
            {nearby?.people.length ? nearby.people.map(p => (
              <Card key={p.id} style={{ marginBottom: 10 }} onPress={() => navigation.navigate('UserProfile', { peerId: p.id })}>
                <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                  <Avatar id={p.id} name={p.name} online />
                  <View style={{ flex: 1, marginLeft: 12 }}>
                    <Text style={[t.font.h3, { color: t.c.text }]}>{p.name}</Text>
                    <View style={{ flexDirection: 'row', gap: 6, marginTop: 5 }}>
                      {p.verified ? <Pill label="Verified" tone="success" icon="shield" /> : <Pill label="Not verified" tone="warning" icon="alert" />}
                      <Pill label="Encrypted" tone="primary" icon="lock" />
                    </View>
                  </View>
                  <Button title="Chat" icon="chat" onPress={() => chat(p.id, p.name)} style={{ paddingVertical: 10, paddingHorizontal: 14 }} />
                </View>
                {p.identityWarning ? <Text style={[t.font.small, { color: t.c.warning, marginTop: 10 }]}>Same name as another contact with a different key. Verify before trusting.</Text> : null}
              </Card>
            )) : <Text style={[t.font.small, { color: t.c.textDim, marginLeft: 4 }]}>No one connected yet.</Text>}

            <SectionLabel>Nearby devices · {nearby?.devices.length ?? 0}</SectionLabel>
            {nearby?.devices.length ? nearby.devices.map(d => {
              const [q, tone] = signal(d.rssi);
              return (
                <Card key={d.address} style={{ marginBottom: 10, flexDirection: 'row', alignItems: 'center' }}>
                  <View style={{ width: 44, height: 44, borderRadius: 22, backgroundColor: t.c.surface2, alignItems: 'center', justifyContent: 'center' }}>
                    <Icon name="bluetooth" color={t.c.textDim} />
                  </View>
                  <View style={{ flex: 1, marginLeft: 12 }}>
                    <Text style={[t.font.h3, { color: t.c.text }]}>Offline Mesh user</Text>
                    <View style={{ flexDirection: 'row', gap: 6, marginTop: 5 }}>
                      <Pill label={`Signal: ${q}`} tone={tone} />
                      <Pill label={d.state === 'connecting' ? 'Connecting…' : 'Identity hidden until connected'} />
                    </View>
                  </View>
                  {d.state === 'discovered' ? <Button title="Connect" kind="secondary" onPress={() => Mesh.connectDevice(d.address)} style={{ paddingVertical: 10, paddingHorizontal: 14 }} /> : null}
                </Card>
              );
            }) : <Text style={[t.font.small, { color: t.c.textDim, marginLeft: 4 }]}>Looking for phones running Offline Mesh… Make sure they have discovery on and are within ~10–30 m.</Text>}

            <Button kind="ghost" title="Not finding anyone? Troubleshoot" icon="help" onPress={() => navigation.navigate('Troubleshooting')} style={{ marginTop: 18 }} />
          </>
        ) : null}
      </Screen>
    </View>
  );
}
