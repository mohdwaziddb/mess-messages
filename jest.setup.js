/* Mock of the native OfflineMesh module: tests exercise the UI/store, not the crypto (covered by JUnit). */
const { NativeModules } = require('react-native');

const state = {
  hasIdentity: true,
  profile: { id: 'abc', name: 'Asha', fingerprint: 'AAAA BBBB' },
  permissionsGranted: true, serviceRunning: true, bleSupported: true, bleEnabled: true, canAdvertise: true,
  visible: true, autoConnect: true, readReceipts: true, relayEnabled: true, wifiDirectSupported: true,
  wifiAwareSupported: false, hardwareBackedKeys: true, batteryOptimized: false, androidVersion: 34, manufacturer: 'Test',
  stats: { connected: 1, contacts: 2, verified: 1, pending: 0, groups: 0, invites: 0, relayQueue: 0, relayed: 0, relayEnabled: true },
};

NativeModules.OfflineMesh = {
  getState: jest.fn(async () => state),
  contacts: jest.fn(async () => []),
  conversations: jest.fn(async () => []),
  groups: jest.fn(async () => []),
  invites: jest.fn(async () => []),
  nearby: jest.fn(async () => ({ people: [], devices: [], links: 0, scanning: true, visible: true })),
  securityEvents: jest.fn(async () => []),
  qrMatrix: jest.fn(async () => ({ size: 2, bits: '1001' })),
  setAppForeground: jest.fn(),
  addListener: jest.fn(),
  removeListeners: jest.fn(),
};

jest.mock('react-native-safe-area-context', () => {
  const inset = { top: 0, right: 0, bottom: 0, left: 0 };
  return {
    SafeAreaProvider: ({ children }) => children,
    useSafeAreaInsets: () => inset,
  };
});
jest.mock('@react-navigation/bottom-tabs', () => ({
  // Outside a tab navigator the real context value is undefined, which is what component tests need.
  BottomTabBarHeightContext: require('react').createContext(undefined),
}));
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ canGoBack: () => false, goBack: jest.fn(), navigate: jest.fn() }),
  useFocusEffect: jest.fn(),
}));
