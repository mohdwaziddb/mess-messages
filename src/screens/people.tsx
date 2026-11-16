import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Pressable, Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import {
  Avatar, Banner, Button, Card, Empty, Group as RowGroup, Header, Input, Pill, Row, Screen, SectionLabel, timeAgo,
} from '../components/ui';
import { Icon } from '../components/Icon';
import { QrView } from '../components/QrView';
import { errorMessage, GroupDetails, Mesh, requestCameraPermission } from '../native/mesh';
import { confirmBlock } from './main';
import { useMesh } from '../store/useMesh';
import { useTheme } from '../theme/theme';

// ------------------------------------------------------------------ Create group

export function CreateGroupScreen({ navigation, route }: any) {
  const t = useTheme();
  const addTo: string | undefined = route.params?.addTo;
  const contacts = useMesh(s => s.contacts.filter(c => !c.blocked));
  const [name, setName] = useState('');
  const [sel, setSel] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const toggle = (id: string) => setSel(s => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });

  const submit = async () => {
    setBusy(true);
    try {
      if (addTo) { await Mesh.addMembers(addTo, [...sel]); navigation.goBack(); }
      else {
        const conv = await Mesh.createGroup(name, [...sel]);
        navigation.replace('GroupChat', { convId: conv, groupId: conv.slice(2), title: name });
      }
      useMesh.getState().refreshLists();
    } catch (e) { Alert.alert('Group', errorMessage(e)); } finally { setBusy(false); }
  };

  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Header title={addTo ? 'Add members' : 'New group'} subtitle={`${sel.size} selected`} />
      <Screen>
        {!addTo ? (
          <>
            <Input value={name} onChangeText={setName} placeholder="Group name" maxLength={48} />
            <Text style={[t.font.small, { color: t.c.textDim, marginTop: 8 }]}>
              Invitations are signed by you and sent encrypted. Members join only after accepting.
            </Text>
          </>
        ) : null}
        <SectionLabel>Contacts</SectionLabel>
        {contacts.length === 0 ? <Empty icon="users" title="No contacts yet" text="Connect with people nearby first. Group members must have met you securely at least once." /> : (
          <RowGroup>
            {contacts.map((c, i) => (
              <Pressable key={c.id} onPress={() => toggle(c.id)} style={{ flexDirection: 'row', alignItems: 'center', padding: 12, borderBottomWidth: i === contacts.length - 1 ? 0 : 1, borderBottomColor: t.c.border }}>
                <Avatar id={c.id} name={c.name} uri={c.avatar} size={40} online={c.connected} />
                <View style={{ flex: 1, marginLeft: 12 }}>
                  <Text style={[t.font.body, { color: t.c.text, fontWeight: '600' }]}>{c.name}</Text>
                  <Text style={[t.font.small, { color: c.verified ? t.c.success : t.c.textDim }]}>{c.verified ? 'Verified' : 'Not verified'}</Text>
                </View>
                <View style={{ width: 24, height: 24, borderRadius: 12, borderWidth: 2, borderColor: sel.has(c.id) ? t.c.primary : t.c.border, backgroundColor: sel.has(c.id) ? t.c.primary : 'transparent', alignItems: 'center', justifyContent: 'center' }}>
                  {sel.has(c.id) ? <Icon name="check" size={14} color="#fff" strokeWidth={3} /> : null}
                </View>
              </Pressable>
            ))}
          </RowGroup>
        )}
        <Button title={addTo ? 'Add to group' : 'Create group'} icon="users" onPress={submit} loading={busy}
          disabled={(!addTo && !name.trim()) || sel.size === 0} style={{ marginTop: 22 }} />
      </Screen>
    </View>
  );
}

// ------------------------------------------------------------------ Group details

export function GroupDetailsScreen({ navigation, route }: any) {
  const t = useTheme();
  const { groupId } = route.params;
  const [g, setG] = useState<GroupDetails | null>(null);
  const [rename, setRename] = useState<string | null>(null);
  const load = useCallback(async () => { try { setG(await Mesh.groupDetails(groupId)); } catch {} }, [groupId]);
  useFocusEffect(useCallback(() => { load(); }, [load]));
  const groupsTick = useMesh(s => s.groups);
  useEffect(() => { load(); }, [groupsTick, load]);
  const myAvatar = useMesh(s => s.state?.profile?.avatar ?? null);
  const contacts = useMesh(s => s.contacts);
  const contactAvatars = React.useMemo(() => Object.fromEntries(contacts.map(c => [c.id, c.avatar ?? null])), [contacts]);

  const act = (fn: () => Promise<any>) => fn().then(load).catch(e => Alert.alert('Group', errorMessage(e)));
  if (!g) return <View style={{ flex: 1, backgroundColor: t.c.bg }}><Header title="Group" /></View>;

  const memberMenu = (m: GroupDetails['members'][number]) => {
    if (m.isMe || !g.isAdmin) return navigation.navigate('UserProfile', { peerId: m.id });
    Alert.alert(m.name, undefined, [
      { text: 'View profile', onPress: () => navigation.navigate('UserProfile', { peerId: m.id }) },
      { text: m.isAdmin ? 'Remove admin' : 'Make admin', onPress: () => act(() => Mesh.setAdmin(groupId, m.id, !m.isAdmin)) },
      { text: 'Remove from group', style: 'destructive', onPress: () => Alert.alert('Remove member?', `${m.name} will not be able to read new messages. Group keys will be rotated.`, [
        { text: 'Cancel' }, { text: 'Remove', style: 'destructive', onPress: () => act(() => Mesh.removeMember(groupId, m.id)) },
      ]) },
      { text: 'Cancel', style: 'cancel' },
    ]);
  };

  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Header title={g.name} subtitle={`${g.members.length} members`} />
      <Screen>
        <View style={{ alignItems: 'center', marginVertical: 10 }}>
          <View style={{ width: 84, height: 84, borderRadius: 28, backgroundColor: t.c.accentSoft, alignItems: 'center', justifyContent: 'center' }}>
            <Icon name="users" size={40} color={t.c.accent} />
          </View>
          {rename !== null ? (
            <View style={{ alignSelf: 'stretch', marginTop: 12 }}>
              <Input value={rename} onChangeText={setRename} maxLength={48} autoFocus />
              <View style={{ flexDirection: 'row', gap: 10, marginTop: 10 }}>
                <Button title="Save" style={{ flex: 1 }} onPress={() => { act(() => Mesh.renameGroup(groupId, rename)); setRename(null); }} />
                <Button title="Cancel" kind="secondary" style={{ flex: 1 }} onPress={() => setRename(null)} />
              </View>
            </View>
          ) : <Text style={[t.font.h2, { color: t.c.text, marginTop: 10 }]}>{g.name}</Text>}
        </View>
        {!g.isMember ? <Banner tone="danger" icon="slash" title="You are no longer a member" text="You can't read new messages in this group." /> : null}

        <SectionLabel>Group security</SectionLabel>
        <Card>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}>
            <Pill label="End-to-end encrypted" tone="success" icon="lock" />
            <Pill label={`Key epoch ${g.epoch}`} tone="primary" icon="key" />
            <Pill label="Signed membership" tone="primary" icon="shield" />
          </View>
          <Text style={[t.font.small, { color: t.c.textDim, marginTop: 10, lineHeight: 19 }]}>
            Every membership change is signed by an admin and starts a new key epoch. Removed members never receive the new keys, so they can't read later messages. Last change {timeAgo(g.lastChange)}.
          </Text>
        </Card>

        <SectionLabel>Members</SectionLabel>
        <RowGroup>
          {g.members.map((m, i) => (
            <Pressable key={m.id} onPress={() => memberMenu(m)} style={{ flexDirection: 'row', alignItems: 'center', padding: 12, borderBottomWidth: i === g.members.length - 1 ? 0 : 1, borderBottomColor: t.c.border }}>
              <Avatar id={m.id} name={m.name} uri={m.isMe ? myAvatar : contactAvatars[m.id]} size={40} online={m.isMe ? undefined : m.connected} />
              <View style={{ flex: 1, marginLeft: 12 }}>
                <Text style={[t.font.body, { color: t.c.text, fontWeight: '600' }]}>{m.name}</Text>
                <Text style={[t.font.small, { color: m.verified ? t.c.success : t.c.textDim }]}>{m.isMe ? 'You' : m.verified ? 'Verified' : 'Not verified'}</Text>
              </View>
              {m.isAdmin ? <Pill label="Admin" tone="primary" /> : null}
            </Pressable>
          ))}
        </RowGroup>

        {g.isMember ? (
          <>
            <SectionLabel>Actions</SectionLabel>
            <RowGroup>
              {g.isAdmin ? <Row icon="plus" title="Add members" onPress={() => navigation.navigate('CreateGroup', { addTo: groupId })} /> : null}
              {g.isAdmin ? <Row icon="edit" title="Rename group" onPress={() => setRename(g.name)} /> : null}
              <Row icon="trash" title="Clear chat history" onPress={() => Alert.alert('Clear history?', 'Deletes messages on this phone only.', [{ text: 'Cancel' }, { text: 'Clear', style: 'destructive', onPress: () => act(() => Mesh.clearConversation(`g:${groupId}`)) }])} />
              <Row icon="logout" title="Leave group" danger last onPress={() => Alert.alert('Leave group?', 'Others will rotate keys; you will not be able to read new messages.', [{ text: 'Cancel' }, { text: 'Leave', style: 'destructive', onPress: () => act(() => Mesh.leaveGroup(groupId)) }])} />
            </RowGroup>
          </>
        ) : null}
      </Screen>
    </View>
  );
}

// ------------------------------------------------------------------ User profile / verification

export function UserProfileScreen({ navigation, route }: any) {
  const t = useTheme();
  const { peerId } = route.params;
  const c = useMesh(s => s.contacts.find(x => x.id === peerId));
  const [safety, setSafety] = useState<string | null>(null);
  useEffect(() => { Mesh.safetyNumber(peerId).then(setSafety).catch(() => {}); }, [peerId]);
  const refresh = () => useMesh.getState().refreshLists();
  if (!c) return <View style={{ flex: 1, backgroundColor: t.c.bg }}><Header title="Contact" /><Empty icon="user" title="Unknown contact" text="" /></View>;

  const chat = async () => {
    try { const conv = await Mesh.openDirect(peerId); navigation.navigate('Chat', { convId: conv, peerId, title: c.name }); }
    catch (e) { Alert.alert('Could not open chat', errorMessage(e)); }
  };
  const groups = safety ? safety.match(/.{1,5}/g) ?? [] : [];

  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Header title={c.name} subtitle={c.connected ? 'Connected nearby' : `Last seen ${timeAgo(c.lastSeen)}`} />
      <Screen>
        <View style={{ alignItems: 'center', marginVertical: 8 }}>
          <Avatar id={c.id} name={c.name} uri={c.avatar} size={86} online={c.connected} />
          <View style={{ flexDirection: 'row', gap: 6, marginTop: 12 }}>
            {c.verified ? <Pill label="Verified" tone="success" icon="shield" /> : <Pill label="Not verified" tone="warning" icon="alert" />}
            {c.blocked ? <Pill label="Blocked" tone="danger" icon="slash" /> : null}
          </View>
        </View>
        {c.identityWarning ? <Banner tone="danger" icon="alert" title="Possible impersonation"
          text="Another contact already uses this name with a different security key. This may be the same person on a new phone — or someone else. Verify in person." /> : null}

        <Button title="Message" icon="chat" onPress={chat} disabled={c.blocked} />

        <SectionLabel>Safety number</SectionLabel>
        <Card>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between' }}>
            {groups.map((g, i) => <Text key={i} style={[t.font.mono, { color: t.c.text, width: '30%', textAlign: 'center', marginVertical: 4, fontSize: 17, letterSpacing: 1 }]}>{g}</Text>)}
          </View>
          <Text style={[t.font.small, { color: t.c.textDim, marginTop: 10, lineHeight: 19 }]}>
            Meet in person and compare these 60 digits with {c.name}'s screen. If they match, your chat is protected from impersonation. Scanning their QR code does this automatically.
          </Text>
          <View style={{ flexDirection: 'row', gap: 10, marginTop: 14 }}>
            {c.verified
              ? <Button title="Mark unverified" kind="secondary" style={{ flex: 1 }} onPress={() => Mesh.setVerified(peerId, false).then(refresh)} />
              : <Button title="Numbers match" icon="check" style={{ flex: 1 }} onPress={() => Alert.alert('Confirm', `Only confirm if you compared every digit with ${c.name} in person.`, [{ text: 'Cancel' }, { text: 'They match', onPress: () => Mesh.setVerified(peerId, true).then(refresh) }])} />}
            <Button title="Scan QR" icon="scan" kind="secondary" style={{ flex: 1 }} onPress={() => navigation.navigate('QrScanner')} />
          </View>
        </Card>

        <SectionLabel>Device key</SectionLabel>
        <Card>
          <Text style={[t.font.tiny, { color: t.c.textFaint }]}>IDENTITY FINGERPRINT</Text>
          <Text style={[t.font.mono, { color: t.c.text, marginTop: 4 }]}>{c.fingerprint}</Text>
          <Text style={[t.font.small, { color: t.c.textDim, marginTop: 8 }]}>First connected {new Date(c.firstSeen).toLocaleDateString()}{c.verifiedAt ? ` · verified ${new Date(c.verifiedAt).toLocaleDateString()}` : ''}</Text>
        </Card>

        <SectionLabel>Privacy</SectionLabel>
        <RowGroup>
          <Row icon="trash" title="Clear conversation" onPress={() => Alert.alert('Clear conversation?', 'Deletes messages on this phone only.', [{ text: 'Cancel' }, { text: 'Clear', style: 'destructive', onPress: () => Mesh.clearConversation(`d:${peerId}`) }])} />
          <Row icon="key" title="Revoke device keys" subtitle="Delete the secure session; a fresh one is created on the next connection" onPress={() => Alert.alert('Revoke keys?', 'Sessions with this device are deleted and verification is reset.', [{ text: 'Cancel' }, { text: 'Revoke', style: 'destructive', onPress: () => Mesh.revoke(peerId).then(refresh) }])} />
          <Row icon="slash" title={c.blocked ? 'Unblock' : 'Block'} danger={!c.blocked} last onPress={() => (c.blocked ? Mesh.block(peerId, false).then(refresh) : confirmBlock(c.name, () => { Mesh.block(peerId, true).then(refresh); }))} />
        </RowGroup>
      </Screen>
    </View>
  );
}

// ------------------------------------------------------------------ QR

export function MyQrScreen({ navigation }: any) {
  const t = useTheme();
  const state = useMesh(s => s.state);
  const [qr, setQr] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [left, setLeft] = useState(300);
  const make = useCallback(async () => {
    setErr(null);
    try { setQr(await Mesh.myQr()); setLeft(300); } catch (e) { setErr(errorMessage(e)); }
  }, []);
  useEffect(() => { make(); }, [make]);
  useEffect(() => { const i = setInterval(() => setLeft(l => { if (l <= 1) { make(); return 300; } return l - 1; }), 1000); return () => clearInterval(i); }, [make]);

  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Header title="My QR code" subtitle="Let a nearby person scan this" />
      <Screen>
        <Card style={{ alignItems: 'center', paddingVertical: 24 }}>
          <Avatar id={state?.profile?.id ?? ''} name={state?.profile?.name ?? ''} uri={state?.profile?.avatar} size={56} />
          <Text style={[t.font.h2, { color: t.c.text, marginTop: 8 }]}>{state?.profile?.name}</Text>
          <View style={{ backgroundColor: '#fff', padding: 14, borderRadius: 20, marginTop: 16 }}>
            {qr ? <QrView value={qr} size={230} /> : <View style={{ width: 230, height: 230, alignItems: 'center', justifyContent: 'center' }}><Icon name="qr" size={60} color="#bbb" /></View>}
          </View>
          {qr ? <Text style={[t.font.small, { color: t.c.textDim, marginTop: 12 }]}>One-time code · refreshes in {Math.floor(left / 60)}:{String(left % 60).padStart(2, '0')}</Text> : null}
          {err ? <Text style={{ color: t.c.warning, marginTop: 12, textAlign: 'center' }}>{err}</Text> : null}
        </Card>
        <SectionLabel>Your fingerprint</SectionLabel>
        <Card><Text style={[t.font.mono, { color: t.c.text, letterSpacing: 1 }]}>{state?.profile?.fingerprint}</Text></Card>
        <Text style={[t.font.small, { color: t.c.textDim, marginTop: 14, lineHeight: 19 }]}>
          The code contains only a one-time token, your public key fingerprint and your current Bluetooth id. No private keys, no name, no phone number. It expires after 5 minutes and works once.
        </Text>
        <Button title="Scan someone else" icon="scan" kind="secondary" onPress={() => navigation.navigate('QrScanner')} style={{ marginTop: 18 }} />
      </Screen>
    </View>
  );
}

export function QrScannerScreen({ navigation }: any) {
  const t = useTheme();
  const [phase, setPhase] = useState<'idle' | 'connecting' | 'verified' | 'error'>('idle');
  const [msg, setMsg] = useState('');
  const contacts = useMesh(s => s.contacts);
  const [expected, setExpected] = useState<number>(0);

  const scan = async () => {
    try {
      if (!(await requestCameraPermission())) { setPhase('error'); setMsg('Camera permission is needed to scan codes.'); return; }
      const text = await Mesh.scanQrCode();
      if (!text) return;
      const r = await Mesh.scanQr(text);
      if (r.status === 'verified') { setPhase('verified'); setMsg('Identity confirmed. This contact is now verified.'); }
      else { setPhase('connecting'); setMsg('Code accepted. Connecting securely and checking their identity…'); setExpected(contacts.filter(c => c.verified).length); }
      useMesh.getState().refreshLists();
    } catch (e) { setPhase('error'); setMsg(errorMessage(e)); }
  };

  useEffect(() => { if (phase === 'connecting' && contacts.filter(c => c.verified).length > expected) { setPhase('verified'); setMsg('Connected and verified. Their key matches the code you scanned.'); } }, [contacts, phase, expected]);
  useEffect(() => { if (phase !== 'connecting') return; const to = setTimeout(() => { setPhase('error'); setMsg('Could not reach that phone. Make sure it is close by with discovery on, then try again.'); }, 45000); return () => clearTimeout(to); }, [phase]);
  useEffect(() => { scan(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const icon = { idle: 'scan', connecting: 'radar', verified: 'shield', error: 'alert' }[phase] as any;
  const color = { idle: t.c.primary, connecting: t.c.primary, verified: t.c.success, error: t.c.warning }[phase];
  return (
    <View style={{ flex: 1, backgroundColor: t.c.bg }}>
      <Header title="Scan QR code" subtitle="Verify and connect in person" />
      <Screen>
        <View style={{ alignItems: 'center', marginTop: 30 }}>
          <View style={{ width: 120, height: 120, borderRadius: 40, backgroundColor: color + '22', alignItems: 'center', justifyContent: 'center' }}>
            <Icon name={icon} size={56} color={color} />
          </View>
          <Text style={[t.font.h2, { color: t.c.text, marginTop: 18, textAlign: 'center' }]}>
            {{ idle: 'Scan a contact', connecting: 'Connecting…', verified: 'Verified', error: 'Verification failed' }[phase]}
          </Text>
          <Text style={[t.font.body, { color: t.c.textDim, marginTop: 8, textAlign: 'center', lineHeight: 22 }]}>
            {msg || 'Ask the other person to open "My QR" and point your camera at it.'}
          </Text>
        </View>
        <Button title={phase === 'idle' ? 'Open camera' : 'Scan again'} icon="scan" onPress={scan} style={{ marginTop: 30 }} />
        {phase === 'verified' ? <Button title="Go to chats" kind="secondary" onPress={() => navigation.navigate('Tabs', { screen: 'Chats' })} style={{ marginTop: 10 }} /> : null}
      </Screen>
    </View>
  );
}
