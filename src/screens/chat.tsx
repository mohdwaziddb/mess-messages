import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert, FlatList, Image, KeyboardAvoidingView, Modal, NativeScrollEvent, NativeSyntheticEvent, Pressable, Text, TextInput, View,
} from 'react-native';
import { confirmBlock } from './main';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Avatar, Banner, Button, bytes, clock, Header, Pill } from '../components/ui';
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

/** Decrypted copy to Downloads/OfflineMesh, only when the user asks. */
export function downloadAttachment(m: Message) {
  Mesh.saveAttachment(m.id)
    .then(p => Alert.alert('Downloaded', `Saved to ${p}`))
    .catch(e => Alert.alert('Download', errorMessage(e)));
}

function openAttachment(m: Message) {
  Mesh.openAttachment(m.id).catch(e => Alert.alert('Open', errorMessage(e)));
}

/** Full-screen photo with Download. */
function ImageViewer({ m, uri, onClose }: { m: Message; uri: string; onClose: () => void }) {
  const insets = useSafeAreaInsets();
  const btn = { flexDirection: 'row' as const, alignItems: 'center' as const, gap: 8, paddingHorizontal: 18, paddingVertical: 12, borderRadius: 24, backgroundColor: 'rgba(255,255,255,0.14)' };
  return (
    <Modal visible animationType="fade" onRequestClose={onClose} statusBarTranslucent>
      <View style={{ flex: 1, backgroundColor: '#000' }}>
        <View style={{ paddingTop: insets.top + 8, paddingHorizontal: 12, flexDirection: 'row', alignItems: 'center' }}>
          <Pressable onPress={onClose} hitSlop={12} style={{ padding: 8 }} accessibilityLabel="Close"><Icon name="x" color="#fff" /></Pressable>
          <Text style={{ color: '#fff', flex: 1, marginLeft: 6, fontWeight: '600' }} numberOfLines={1}>{m.body}</Text>
        </View>
        <Image source={{ uri }} style={{ flex: 1 }} resizeMode="contain" accessibilityLabel={m.body} />
        <View style={{ flexDirection: 'row', justifyContent: 'center', gap: 12, paddingBottom: insets.bottom + 16, paddingTop: 12 }}>
          <Pressable onPress={() => downloadAttachment(m)} style={btn} accessibilityLabel="Download">
            <Icon name="download" color="#fff" size={18} /><Text style={{ color: '#fff', fontWeight: '600' }}>Download</Text>
          </Pressable>
          <Pressable onPress={() => openAttachment(m)} style={btn} accessibilityLabel="Open in another app">
            <Icon name="external" color="#fff" size={18} /><Text style={{ color: '#fff', fontWeight: '600' }}>Open with…</Text>
          </Pressable>
        </View>
      </View>
    </Modal>
  );
}

function Attachment({ m }: { m: Message }) {
  const t = useTheme();
  const [uri, setUri] = useState<string | null>(null);
  const [viewing, setViewing] = useState(false);
  const isImage = (m.mime ?? '').startsWith('image/');
  useEffect(() => { if (isImage && m.complete) Mesh.attachmentDataUri(m.id).then(setUri).catch(() => {}); }, [m.id, m.complete, isImage]);
  const fg = m.outgoing ? t.c.bubbleOutText : t.c.bubbleInText;
  const progress = !m.complete && m.progress != null ? `${Math.round(m.progress * 100)}%` : null;

  if (isImage && uri) {
    return (
      <>
        <Pressable onPress={() => setViewing(true)} accessibilityLabel={`Photo ${m.body}, tap to view`}>
          <Image source={{ uri }} style={{ width: 220, height: 220, borderRadius: 12, marginBottom: 4 }} resizeMode="cover" />
        </Pressable>
        {viewing ? <ImageViewer m={m} uri={uri} onClose={() => setViewing(false)} /> : null}
      </>
    );
  }
  const small = { flexDirection: 'row' as const, alignItems: 'center' as const, gap: 5, paddingHorizontal: 10, paddingVertical: 6, borderRadius: 14, backgroundColor: 'rgba(127,127,127,0.18)' };
  return (
    <View style={{ minWidth: 200 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center' }}>
        <View style={{ width: 38, height: 38, borderRadius: 10, backgroundColor: 'rgba(127,127,127,0.18)', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name={isImage ? 'eye' : 'file'} size={19} color={fg} />
        </View>
        <View style={{ marginLeft: 10, flexShrink: 1 }}>
          <Text style={{ color: fg, fontWeight: '600' }} numberOfLines={1}>{m.body}</Text>
          <Text style={{ color: fg, opacity: 0.7, fontSize: 12 }}>
            {m.size ? bytes(m.size) : ''}{progress ? ` · ${m.outgoing ? 'sending' : 'receiving'} ${progress}` : ''}
          </Text>
        </View>
      </View>
      {m.complete ? (
        <View style={{ flexDirection: 'row', gap: 8, marginTop: 8 }}>
          <Pressable onPress={() => openAttachment(m)} style={small} accessibilityLabel="Open file">
            <Icon name="external" size={14} color={fg} /><Text style={{ color: fg, fontSize: 13, fontWeight: '600' }}>Open</Text>
          </Pressable>
          <Pressable onPress={() => downloadAttachment(m)} style={small} accessibilityLabel="Download file">
            <Icon name="download" size={14} color={fg} /><Text style={{ color: fg, fontSize: 13, fontWeight: '600' }}>Download</Text>
          </Pressable>
        </View>
      ) : null}
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

  // Newest-first data in an inverted list: opens at the latest message, stays put while the user reads
  // older ones, and never jumps when an image finishes loading.
  const listData = React.useMemo(() => [...messages].reverse(), [messages]);
  const atBottom = useRef(true);
  const [unseen, setUnseen] = useState(0);
  const lastNewest = useRef<string | null>(null);
  useEffect(() => {
    const newest = listData[0]?.id ?? null;
    if (lastNewest.current && newest && newest !== lastNewest.current && !atBottom.current && !listData[0].outgoing) setUnseen(n => n + 1);
    lastNewest.current = newest;
  }, [listData]);
  const toLatest = (animated = true) => { listRef.current?.scrollToOffset({ offset: 0, animated }); setUnseen(0); };
  const onScroll = (e: NativeSyntheticEvent<NativeScrollEvent>) => {
    atBottom.current = e.nativeEvent.contentOffset.y < 80;
    if (atBottom.current && unseen) setUnseen(0);
  };
  // Chat screen is open: everything shown counts as read (sends read receipts if enabled).
  useEffect(() => { Mesh.markRead(convId).catch(() => {}); }, [convId, tick]);

  const send = async () => {
    const body = text.trim();
    if (!body) return;
    setSending(true);
    try { await Mesh.sendText(convId, body); setText(''); await load(); toLatest(false); }
    catch (e) { Alert.alert('Not sent', errorMessage(e)); } finally { setSending(false); }
  };

  const attach = async () => {
    try {
      const uri = await Mesh.pickFile();
      if (!uri) return;
      await Mesh.sendFile(convId, uri);
      await load(); toLatest(false);
    } catch (e) { Alert.alert('File not sent', errorMessage(e)); }
  };

  const onLong = (m: Message) => {
    const actions: { text: string; onPress?: () => void; style?: any }[] = [];
    if (m.outgoing && m.status === 'failed' && m.kind === 'text') actions.push({ text: 'Retry', onPress: () => Mesh.retryMessage(m.id).then(load).catch(e => Alert.alert('Retry', errorMessage(e))) });
    if (m.kind === 'file' && m.complete) {
      actions.push({ text: 'Open', onPress: () => openAttachment(m) });
      actions.push({ text: 'Download', onPress: () => downloadAttachment(m) });
    }
    actions.push({ text: 'Delete for me', style: 'destructive', onPress: () => Mesh.deleteMessage(m.id).then(load) });
    actions.push({ text: 'Cancel', style: 'cancel' });
    Alert.alert(statusLabel[m.status] ?? m.status, new Date(m.sentAt).toLocaleString(), actions);
  };

  const peerId: string | undefined = conv?.peerId ?? route.params.peerId;
  const blocked = !isGroup && !!(contact?.blocked ?? conv?.blocked);
  const refreshLists = () => useMesh.getState().refreshLists();
  const setBlocked = (b: boolean) => peerId && Mesh.block(peerId, b).then(refreshLists).catch(e => Alert.alert(b ? 'Block' : 'Unblock', errorMessage(e)));

  const chatMenu = () => {
    const actions: { text: string; style?: 'cancel' | 'destructive'; onPress?: () => void }[] = [
      { text: isGroup ? 'Group info' : 'View contact', onPress: () => (isGroup
        ? navigation.navigate('GroupDetails', { groupId: groupId ?? String(convId).slice(2) })
        : navigation.navigate('UserProfile', { peerId })) },
      { text: conv?.pinned ? 'Unpin chat' : 'Pin chat', onPress: () => Mesh.setPinned(convId, !conv?.pinned).then(refreshLists).catch(e => Alert.alert('Pin', errorMessage(e))) },
    ];
    if (!isGroup && peerId) {
      actions.push(blocked ? { text: 'Unblock', onPress: () => setBlocked(false) }
        : { text: 'Block', style: 'destructive', onPress: () => confirmBlock(title, () => setBlocked(true)) });
    }
    actions.push({ text: 'Clear chat', style: 'destructive', onPress: () => Alert.alert('Clear chat?', 'Messages in this chat are deleted from this phone only.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Clear', style: 'destructive', onPress: () => Mesh.clearConversation(convId).then(() => { load(); refreshLists(); }).catch(e => Alert.alert('Clear chat', errorMessage(e))) },
    ]) });
    actions.push({ text: 'Cancel', style: 'cancel' });
    Alert.alert(title, undefined, actions);
  };

  const title = conv?.title ?? route.params.title ?? 'Chat';
  const subtitle = isGroup
    ? (conv && !conv.active ? 'You are no longer a member' : 'Group · end-to-end encrypted')
    : conv?.connected ? 'Connected nearby · encrypted' : 'Not in range · messages will wait';

  return (
    <KeyboardAvoidingView style={{ flex: 1, backgroundColor: t.c.bg }} behavior="height">
      <Header title={title} subtitle={blocked ? 'Blocked' : subtitle} right={
        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
          <Pressable hitSlop={10} style={{ padding: 6 }} onPress={() => isGroup
            ? navigation.navigate('GroupDetails', { groupId: groupId ?? String(convId).slice(2) })
            : navigation.navigate('UserProfile', { peerId })}>
            {isGroup ? <Icon name="users" color={t.c.primary} /> : <Avatar id={peerId ?? ''} name={title} uri={contact?.avatar ?? conv?.avatar} size={34} online={conv?.connected} />}
          </Pressable>
          <Pressable hitSlop={10} style={{ padding: 6, marginLeft: 2 }} onPress={chatMenu} accessibilityLabel="Chat options">
            <Icon name="more" color={t.c.text} />
          </Pressable>
        </View>
      } />
      {!isGroup && contact && !contact.verified ? (
        <View style={{ paddingHorizontal: 12 }}>
          <Banner tone={contact.identityWarning ? 'danger' : 'warning'} icon={contact.identityWarning ? 'alert' : 'shield'}
            title={contact.identityWarning ? 'Identity warning' : 'Not verified yet'}
            text={contact.identityWarning ? 'This person uses the same name as another contact but a different key. Verify in person before sharing anything sensitive.' : 'Compare safety numbers or scan their QR code in person to make sure no one is impersonating them.'}
            action={<Pressable onPress={() => navigation.navigate('UserProfile', { peerId: contact.id })}><Text style={{ color: t.c.primary, fontWeight: '600' }}>Verify now</Text></Pressable>} />
        </View>
      ) : null}
      {messages.length === 0 ? (
        // Rendered outside the inverted list (an empty component inside it would appear upside down).
        <View style={{ flex: 1, alignItems: 'center', padding: 32 }}>
          <Pill label="Messages are end-to-end encrypted" tone="primary" icon="lock" />
          <Text style={[t.font.small, { color: t.c.textDim, textAlign: 'center', marginTop: 10 }]}>
            Nobody else — not even phones that relay your messages — can read them.
          </Text>
        </View>
      ) : (
        <View style={{ flex: 1 }}>
          <FlatList
            ref={listRef}
            inverted
            data={listData}
            keyExtractor={m => m.id}
            renderItem={({ item }) => <Bubble m={item} group={isGroup} onLong={onLong} />}
            onScroll={onScroll}
            scrollEventThrottle={100}
            keyboardShouldPersistTaps="handled"
            maintainVisibleContentPosition={{ minIndexForVisible: 1, autoscrollToTopThreshold: 80 }}
            contentContainerStyle={{ paddingVertical: 10 }}
          />
          {unseen > 0 ? (
            <Pressable onPress={() => toLatest()} accessibilityLabel="Jump to latest message"
              style={{ position: 'absolute', right: 14, bottom: 12, flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: t.c.primary, paddingHorizontal: 14, paddingVertical: 9, borderRadius: 20, elevation: 4 }}>
              <Icon name="arrowDown" size={16} color="#fff" />
              <Text style={{ color: '#fff', fontWeight: '600' }}>{unseen} new</Text>
            </Pressable>
          ) : null}
        </View>
      )}
      {blocked ? (
        <View style={{ padding: 14, paddingBottom: insets.bottom + 14, borderTopWidth: 1, borderTopColor: t.c.border, alignItems: 'center' }}>
          <Text style={[t.font.small, { color: t.c.textDim, textAlign: 'center' }]}>You blocked this contact. They can't connect to you or message you.</Text>
          <Button title="Unblock" kind="secondary" onPress={() => setBlocked(false)} style={{ marginTop: 10, alignSelf: 'stretch' }} />
        </View>
      ) : conv && !conv.active ? null : (
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
