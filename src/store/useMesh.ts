import { create } from 'zustand';
import {
  Contact, Conversation, Group, Invite, Mesh, MeshState, meshEvents, Nearby, SecurityEvent, Stats,
} from '../native/mesh';

type Store = {
  ready: boolean;
  error: string | null;
  state: MeshState | null;
  nearby: Nearby | null;
  contacts: Contact[];
  conversations: Conversation[];
  groups: Group[];
  invites: Invite[];
  security: SecurityEvent[];
  /** Incremented when messages change, per conversation id. Screens re-query on change. */
  messageTick: Record<string, number>;
  refreshState: () => Promise<void>;
  refreshNearby: () => Promise<void>;
  refreshLists: () => Promise<void>;
  refreshSecurity: () => Promise<void>;
};

const emptyStats: Stats = { connected: 0, contacts: 0, verified: 0, pending: 0, groups: 0, invites: 0, relayQueue: 0, relayed: 0, relayEnabled: true };

export const useMesh = create<Store>((set, get) => ({
  ready: false,
  error: null,
  state: null,
  nearby: null,
  contacts: [],
  conversations: [],
  groups: [],
  invites: [],
  security: [],
  messageTick: {},

  refreshState: async () => {
    // Storage opens asynchronously on first launch (Keystore + SQLCipher); retry briefly.
    for (let i = 0; i < 40; i++) {
      try {
        const state = await Mesh.getState();
        set({ state, ready: true, error: null });
        return;
      } catch (e: any) {
        if (e?.code !== 'E_NOT_READY') { set({ error: e?.message ?? 'Failed to open secure storage', ready: true }); return; }
        await new Promise<void>(r => setTimeout(() => r(), 150));
      }
    }
    set({ error: 'Secure storage did not open', ready: true });
  },
  refreshNearby: async () => {
    try { set({ nearby: await Mesh.nearby() }); } catch {}
  },
  refreshLists: async () => {
    if (!get().state?.hasIdentity) return;
    try {
      const [contacts, conversations, groups, invites] = await Promise.all([
        Mesh.contacts(), Mesh.conversations(), Mesh.groups(), Mesh.invites(),
      ]);
      set({ contacts, conversations, groups, invites });
    } catch {}
  },
  refreshSecurity: async () => {
    try { set({ security: await Mesh.securityEvents() }); } catch {}
  },
}));

export const stats = (s: MeshState | null): Stats => s?.stats ?? emptyStats;

let timers: Record<string, ReturnType<typeof setTimeout>> = {};
function debounce(key: string, fn: () => void, ms = 120) {
  clearTimeout(timers[key]);
  timers[key] = setTimeout(fn, ms);
}

/** Subscribes the store to native change events. Returns an unsubscribe function. */
export function startMeshSync(): () => void {
  const subs = [
    meshEvents?.addListener('mesh:change', (ev: any) => {
      const s = useMesh.getState();
      switch (ev.kind) {
        case 'nearby':
        case 'links':
          debounce('nearby', s.refreshNearby);
          debounce('lists', s.refreshLists);
          debounce('state', s.refreshState, 400);
          break;
        case 'messages':
          if (ev.id) useMesh.setState(st => ({ messageTick: { ...st.messageTick, [ev.id!]: (st.messageTick[ev.id!] ?? 0) + 1 } }));
          debounce('lists', s.refreshLists);
          break;
        case 'conversations':
        case 'contacts':
        case 'groups':
          debounce('lists', s.refreshLists);
          break;
        case 'security':
          debounce('security', s.refreshSecurity);
          debounce('lists', s.refreshLists);
          break;
        case 'stats':
          debounce('state', s.refreshState, 500);
          break;
      }
    }),
  ];
  const poll = setInterval(() => useMesh.getState().refreshNearby(), 4000);
  return () => { subs.forEach(x => x?.remove()); clearInterval(poll); };
}
