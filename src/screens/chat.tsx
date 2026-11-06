import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert, FlatList, Image, KeyboardAvoidingView, Pressable, Text, TextInput, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Avatar, Banner, bytes, clock, Header, Pill } from '../components/ui';
import { Icon } from '../components/Icon';
import { errorMessage, Mesh, Message } from '../native/mesh';
import { useMesh } from '../store/useMesh';
import { useTheme } from '../theme/theme';

function StatusIcon({ m }: { m: Message }) {
  const t = useTheme();
  const col = 'rgba(255,255,255,0.8)';
  switch (m.status) {
    case 'pending': return <Icon name="clock" size={13} color={col} />;
    case 'sent': return <Icon name="check" size={14} color={col} />;
    case 'delivered': return <Icon name="checks" size={15} color={col} />;
    case 'read': return <Icon name="checks" size={15} color="#7CF3D8" />;
    case 'failed': return <Icon name="alert" size={14} color={t.c.warning} />;
    default: return null;
  }
}

const statusLabel: Record<string, string> = {
  pending: 'Waiting for a path to the recipient', sent: 'Sent — no receipt yet', delivered: 'Delivered', read: 'Read',
  failed: 'Not delivered (expired)', received: 'Received', receiving: 'Receiving…',
};

function Attachment({ m }: { m: Message }) {
  const t = useTheme();
  const [uri, setUri] = useState<string | null>(null);
  const isImage = (m.mime ?? '').startsWith('image/');
  useEffect(() => { if (isImage && m.complete) Mesh.attachmentDataUri(m.id).then(setUri).catch(() => {}); }, [m.id, m.complete, isImage]);
  const fg = m.outgoing ? t.c.bubbleOutText : t.c.bubbleInText;
  if (isImage && uri) return <Image source={{ uri }} style={{ width: 220, height: 220, borderRadius: 12, marginBottom: 4 }} resizeMode="cover" />;
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', minWidth: 180 }}>
      <View style={{ width: 38, height: 38, borderRadius: 10, backgroundColor: 'rgba(127,127,127,0.18)', alignItems: 'center', justifyContent: 'center' }}>
        <Icon name="file" size={19} color={fg} />
      </View>
      <View style={{ marginLeft: 10, flexShrink: 1 }}>
        <Text style={{ color: fg, fontWeight: '600' }} numberOfLines={1}>{m.body}</Text>
        <Text style={{ color: fg, opacity: 0.7, fontSize: 12 }}>
          {m.size ? bytes(m.size) : ''}{!m.complete && m.progress != null ? ` · ${Math.round(m.progress * 100)}%` : ''}
        </Text>
      </View>
    </View>
  );
}

function Bubble({ m, group, onLong }: { m: Message; group: boolean; onLong: (m: Message) => void }) {
  const t = useTheme();
  if (m.kind === 'system') {
    return (
      <View style={{ alignItems: 'center', marginVertical: 8 }}>
        <View style={{ backgroundColor: t.c.surface2, borderRadius: 999, paddingHorizontal: 12, paddingVertical: 5 }}>
          <Text style={[t.font.tiny, { color: t.c.textDim }]}>{m.body}</Text>
        </View>
      </View>
    );
  }
  const out = m.outgoing;
  return (
    <Pressable onLongPress={() => onLong(m)} delayLongPress={300} style={{ alignItems: out ? 'flex-end' : 'flex-start', marginVertical: 3, paddingHorizontal: 12 }}>
      <View style={{
        maxWidth: '82%', backgroundColor: out ? t.c.bubbleOut : t.c.bubbleIn, borderRadius: 20,
        borderBottomRightRadius: out ? 6 : 20, borderBottomLeftRadius: out ? 20 : 6, paddingHorizontal: 13, paddingVertical: 9,
        borderWidth: out ? 0 : 1, borderColor: t.c.border,
      }}>
        {group && !out ? <Text style={{ color: t.c.accent, fontWeight: '600', fontSize: 12.5, marginBottom: 2 }}>{m.senderName || 'Member'}</Text> : null}
        {m.kind === 'file' ? <Attachment m={m} /> : <Text style={{ color: out ? t.c.bubbleOutText : t.c.bubbleInText, fontSize: 15.5, lineHeight: 21 }}>{m.body}</Text>}
        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'flex-end', marginTop: 3, gap: 4 }}>
          <Text style={{ color: out ? 'rgba(255,255,255,0.75)' : t.c.textFaint, fontSize: 11 }}>{clock(m.sentAt)}</Text>
          {out ? <StatusIcon m={m} /> : null}
        </View>
      </View>
      {out && m.status === 'failed' ? <Text style={{ color: t.c.warning, fontSize: 12, marginTop: 3 }}>Not delivered · long-press to retry</Text> : null}
    </Pressable>
  );
}

export function ChatScreen({ route, navigation }: any) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const { convId, groupId } = route.params;
  const isGroup = !!groupId || String(convId).startsWith('g:');
  const conv = useMesh(s => s.conversations.find(c => c.id === convId));
  const contact = useMesh(s => s.contacts.find(c => c.id === conv?.peerId));
  const tick = useMesh(s => s.messageTick[convId] ?? 0);
  const [messages, setMessages] = useState<Message[]>([]);
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const listRef = useRef<FlatList>(null);

  const load = useCallback(async () => {
    try { setMessages(await Mesh.messages(convId, 300)); } catch {}
  }, [convId]);

  useEffect(() => { load(); }, [load, tick]);
  // Chat screen is open: everything shown counts as read (sends read receipts if enabled).
  useEffect(() => { Mesh.markRead(convId).catch(() => {}); }, [convId, tick]);

  const send = async () => {
    const body = text.trim();
    if (!body) return;
    setSending(true);
    try { await Mesh.sendText(convId, body); setText(''); load(); }
    catch (e) { Alert.alert('Not sent', errorMessage(e)); } finally { setSending(false); }
  };

  const attach = async () => {
    try {
      const uri = await Mesh.pickFile();
      if (!uri) return;
      await Mesh.sendFile(convId, uri);
      load();
    } catch (e) { Alert.alert('File not sent', errorMessage(e)); }
  };

  const onLong = (m: Message) => {
    const actions: { text: string; onPress?: () => void; style?: any }[] = [];
    if (m.outgoing && m.status === 'failed' && m.kind === 'text') actions.push({ text: 'Retry', onPress: () => Mesh.retryMessage(m.id).then(load).catch(e => Alert.alert('Retry', errorMessage(e))) });
    if (m.kind === 'file' && m.complete) actions.push({ text: 'Save to Downloads', onPress: () => Mesh.saveAttachment(m.id).then(p => Alert.alert('Saved', p)).catch(e => Alert.alert('Save', errorMessage(e))) });
    actions.push({ text: 'Delete for me', style: 'destructive', onPress: () => Mesh.deleteMessage(m.id).then(load) });
    actions.push({ text: 'Cancel', style: 'cancel' });
    Alert.alert(statusLabel[m.status] ?? m.status, new Date(m.sentAt).toLocaleString(), actions);
  };

  const title = conv?.title ?? route.params.title ?? 'Chat';
  const subtitle = isGroup
    ? (conv && !conv.active ? 'You are no longer a member' : 'Group · end-to-end encrypted')
    : conv?.connected ? 'Connected nearby · encrypted' : 'Not in range · messages will wait';

  return (
    <KeyboardAvoidingView style={{ flex: 1, backgroundColor: t.c.bg }} behavior="height">
      <Header title={title} subtitle={subtitle} right={
        <Pressable hitSlop={10} style={{ padding: 6 }} onPress={() => isGroup
          ? navigation.navigate('GroupDetails', { groupId: groupId ?? String(convId).slice(2) })
          : navigation.navigate('UserProfile', { peerId: conv?.peerId ?? route.params.peerId })}>
          {isGroup ? <Icon name="users" color={t.c.primary} /> : <Avatar id={conv?.peerId ?? ''} name={title} size={34} online={conv?.connected} />}
        </Pressable>
      } />
      {!isGroup && contact && !contact.verified ? (
        <View style={{ paddingHorizontal: 12 }}>
          <Banner tone={contact.identityWarning ? 'danger' : 'warning'} icon={contact.identityWarning ? 'alert' : 'shield'}
            title={contact.identityWarning ? 'Identity warning' : 'Not verified yet'}
            text={contact.identityWarning ? 'This person uses the same name as another contact but a different key. Verify in person before sharing anything sensitive.' : 'Compare safety numbers or scan their QR code in person to make sure no one is impersonating them.'}
            action={<Pressable onPress={() => navigation.navigate('UserProfile', { peerId: contact.id })}><Text style={{ color: t.c.primary, fontWeight: '600' }}>Verify now</Text></Pressable>} />
        </View>
      ) : null}
      <FlatList
        ref={listRef}
        data={messages}
        keyExtractor={m => m.id}
        renderItem={({ item }) => <Bubble m={item} group={isGroup} onLong={onLong} />}
        onContentSizeChange={() => listRef.current?.scrollToEnd({ animated: false })}
        contentContainerStyle={{ paddingVertical: 10 }}
        ListEmptyComponent={
          <View style={{ alignItems: 'center', padding: 32 }}>
            <Pill label="Messages are end-to-end encrypted" tone="primary" icon="lock" />
            <Text style={[t.font.small, { color: t.c.textDim, textAlign: 'center', marginTop: 10 }]}>
              Nobody else — not even phones that relay your messages — can read them.
            </Text>
          </View>
        }
      />
      {conv && !conv.active ? null : (
        <View style={{ flexDirection: 'row', alignItems: 'flex-end', paddingHorizontal: 10, paddingTop: 8, paddingBottom: insets.bottom + 8, backgroundColor: t.c.bg, borderTopWidth: 1, borderTopColor: t.c.border }}>
          <Pressable onPress={attach} hitSlop={8} style={{ padding: 10 }} accessibilityLabel="Attach file"><Icon name="paperclip" color={t.c.textDim} /></Pressable>
          <TextInput value={text} onChangeText={setText} placeholder="Encrypted message" placeholderTextColor={t.c.textFaint} multiline maxLength={8000}
            style={{ flex: 1, maxHeight: 130, backgroundColor: t.c.surface, color: t.c.text, borderRadius: 22, paddingHorizontal: 16, paddingTop: 11, paddingBottom: 11, fontSize: 15.5, borderWidth: 1, borderColor: t.c.border }} />
          <Pressable onPress={send} disabled={!text.trim() || sending} accessibilityLabel="Send"
            style={({ pressed }) => ({ marginLeft: 8, width: 46, height: 46, borderRadius: 23, backgroundColor: text.trim() ? t.c.primary : t.c.surface2, alignItems: 'center', justifyContent: 'center', opacity: pressed ? 0.8 : 1 })}>
            <Icon name="send" size={20} color={text.trim() ? '#fff' : t.c.textFaint} />
          </Pressable>
        </View>
      )}
    </KeyboardAvoidingView>
  );
}

// Group chat uses the same screen; the distinction is the group id.
export const GroupChatScreen = ChatScreen;
