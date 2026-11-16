import { NativeEventEmitter, NativeModules, PermissionsAndroid, Platform } from 'react-native';

/**
 * Typed wrapper over the native OfflineMesh module.
 * The JS layer only ever sees public data: no private keys, no ciphertext handling.
 */

export type Profile = { id: string; name: string; fingerprint: string; createdAt?: number | null; avatar?: string | null };

export type Stats = {
  connected: number;
  contacts: number;
  verified: number;
  pending: number;
  groups: number;
  invites: number;
  relayQueue: number;
  relayed: number;
  relayEnabled: boolean;
};

export type MeshState = {
  hasIdentity: boolean;
  profile: Profile | null;
  permissionsGranted: boolean;
  serviceRunning: boolean;
  bleSupported: boolean;
  bleEnabled: boolean;
  canAdvertise: boolean;
  visible: boolean;
  autoConnect: boolean;
  readReceipts: boolean;
  relayEnabled: boolean;
  wifiDirectSupported: boolean;
  wifiAwareSupported: boolean;
  hardwareBackedKeys: boolean;
  batteryOptimized: boolean;
  androidVersion: number;
  manufacturer: string;
  stats: Stats;
  locationEnabled?: boolean;
  /** Live radio diagnostics (Troubleshooting screen). Optional: older native builds don't send it. */
  radio?: { advertising: string; scanning: string; bluetoothSignals: number; meshPhonesSeen: number };
};

export type Contact = {
  id: string;
  name: string;
  verified: boolean;
  verifiedAt: number | null;
  blocked: boolean;
  identityWarning: boolean;
  lastSeen: number;
  firstSeen: number;
  connected: boolean;
  fingerprint: string;
  avatar?: string | null;
};

export type NearbyDevice = { type: 'device'; address: string; ephemeralId: string; rssi: number; state: string; lastSeen: number };
export type Nearby = {
  people: (Contact & { type: 'person' })[];
  devices: NearbyDevice[];
  links: number;
  scanning: boolean;
  visible: boolean;
};

export type Conversation = {
  id: string;
  kind: 'direct' | 'group';
  peerId: string | null;
  groupId: string | null;
  title: string;
  lastMsgAt: number;
  preview: string;
  unread: number;
  verified: boolean;
  identityWarning: boolean;
  connected: boolean;
  active: boolean;
  pinned?: boolean;
  blocked?: boolean;
  avatar?: string | null;
};

export type MessageStatus = 'pending' | 'sent' | 'delivered' | 'read' | 'failed' | 'received' | 'receiving';
export type Message = {
  id: string;
  senderId: string;
  senderName: string;
  outgoing: boolean;
  kind: 'text' | 'file' | 'system';
  body: string;
  status: MessageStatus;
  sentAt: number;
  receivedAt: number;
  mime: string | null;
  size: number | null;
  complete: boolean;
  progress: number | null;
};

export type Group = { id: string; convId: string; name: string; epoch: number; active: boolean; isAdmin: boolean; memberCount: number };
export type GroupMember = { id: string; name: string; isAdmin: boolean; isMe: boolean; verified: boolean; connected: boolean };
export type GroupDetails = {
  id: string; name: string; epoch: number; isAdmin: boolean; isMember: boolean; lastChange: number; members: GroupMember[];
};
export type Invite = { groupId: string; name: string; from: string; memberCount: number; receivedAt: number };
export type SecurityEvent = { id: number; at: number; kind: string; contactId: string | null; detail: string };
export type StorageInfo = {
  messages: number; attachments: number; attachmentBytes: number; outbox: number; relayQueue: number; relayBytes: number; encryptedFileBytes: number;
};

type Native = {
  getState(): Promise<MeshState>;
  createIdentity(name: string): Promise<Profile>;
  setMyName(name: string): Promise<Profile>;
  pickAvatar(): Promise<Profile | null>;
  removeAvatar(): Promise<Profile>;
  debugLog(): Promise<string>;
  setPinned(convId: string, pinned: boolean): Promise<boolean>;
  openAttachment(id: string): Promise<boolean>;
  requiredPermissions(): Promise<string[]>;
  startService(): Promise<boolean>;
  stopService(): Promise<boolean>;
  setAppForeground(fg: boolean): void;
  nearby(): Promise<Nearby>;
  connectDevice(address: string): Promise<boolean>;
  contacts(): Promise<Contact[]>;
  conversations(): Promise<Conversation[]>;
  messages(convId: string, limit: number): Promise<Message[]>;
  groups(): Promise<Group[]>;
  groupDetails(groupId: string): Promise<GroupDetails | null>;
  invites(): Promise<Invite[]>;
  securityEvents(): Promise<SecurityEvent[]>;
  stats(): Promise<Stats>;
  storageInfo(): Promise<StorageInfo>;
  openDirect(peerId: string): Promise<string>;
  sendText(convId: string, text: string): Promise<string>;
  retryMessage(id: string): Promise<boolean>;
  deleteMessage(id: string): Promise<boolean>;
  clearConversation(convId: string): Promise<boolean>;
  markRead(convId: string): Promise<boolean>;
  sendFile(convId: string, uri: string): Promise<string>;
  attachmentDataUri(id: string): Promise<string | null>;
  saveAttachment(id: string): Promise<string>;
  createGroup(name: string, members: string[]): Promise<string>;
  addMembers(groupId: string, members: string[]): Promise<boolean>;
  removeMember(groupId: string, memberId: string): Promise<boolean>;
  setAdmin(groupId: string, memberId: string, admin: boolean): Promise<boolean>;
  renameGroup(groupId: string, name: string): Promise<boolean>;
  leaveGroup(groupId: string): Promise<boolean>;
  acceptInvite(groupId: string): Promise<string>;
  rejectInvite(groupId: string): Promise<boolean>;
  myQr(): Promise<string>;
  scanQr(text: string): Promise<{ status: 'verified' | 'connecting'; ephemeralId?: string }>;
  scanQrCode(): Promise<string | null>;
  qrMatrix(text: string): Promise<{ size: number; bits: string }>;
  pickFile(): Promise<string | null>;
  safetyNumber(peerId: string): Promise<string>;
  setVerified(peerId: string, verified: boolean): Promise<boolean>;
  block(peerId: string, blocked: boolean): Promise<boolean>;
  revoke(peerId: string): Promise<boolean>;
  setSetting(key: 'relayEnabled' | 'visible' | 'autoConnect' | 'readReceipts', value: boolean): Promise<boolean>;
  clearRelayQueue(): Promise<boolean>;
  openBatterySettings(): Promise<boolean>;
  openAppSettings(): Promise<boolean>;
  openBluetoothSettings(): Promise<boolean>;
  wipeAllData(): Promise<boolean>;
};

export const Mesh: Native = NativeModules.OfflineMesh;
export const meshEvents = Mesh ? new NativeEventEmitter(NativeModules.OfflineMesh) : null;

export type PermissionResult = { granted: boolean; blocked: boolean };

/** Ask only for what the running Android version actually needs. */
export async function requestNearbyPermissions(): Promise<PermissionResult> {
  if (Platform.OS !== 'android') return { granted: false, blocked: false };
  const perms = (await Mesh.requiredPermissions()) as any[];
  const res = await PermissionsAndroid.requestMultiple(perms);
  const values = Object.values(res);
  return {
    granted: values.every(v => v === PermissionsAndroid.RESULTS.GRANTED),
    blocked: values.some(v => v === PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN),
  };
}

export async function requestNotificationPermission() {
  if (Platform.OS === 'android' && Platform.Version >= 33) {
    await PermissionsAndroid.request('android.permission.POST_NOTIFICATIONS' as any);
  }
}

export async function requestCameraPermission(): Promise<boolean> {
  const r = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.CAMERA);
  return r === PermissionsAndroid.RESULTS.GRANTED;
}

export function errorMessage(e: unknown): string {
  if (e && typeof e === 'object' && 'message' in e) return String((e as any).message);
  return 'Something went wrong';
}
