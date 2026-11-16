import React, { useEffect } from 'react';
import { AppState, Pressable, Text, View } from 'react-native';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import { DarkTheme, DefaultTheme, NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { Icon, IconName } from './src/components/Icon';
import { Mesh } from './src/native/mesh';
import { startMeshSync, useMesh } from './src/store/useMesh';
import { useTheme } from './src/theme/theme';
import {
  CreateProfileScreen, OnboardingScreen, SecuritySetupScreen, SplashScreen, useOnboarding, WelcomeScreen,
} from './src/screens/onboarding';
import { ChatScreen, GroupChatScreen } from './src/screens/chat';
import { ChatsScreen, HomeScreen, NearbyScreen } from './src/screens/main';
import {
  CreateGroupScreen, GroupDetailsScreen, MyQrScreen, QrScannerScreen, UserProfileScreen,
} from './src/screens/people';
import {
  MeshDashboardScreen, PrivacyScreen, SecurityCenterScreen, SettingsScreen, StorageScreen, TroubleshootingScreen, VerifiedDevicesScreen,
} from './src/screens/system';

const Stack = createNativeStackNavigator();
const Tab = createBottomTabNavigator();

const tabIcons: Record<string, IconName> = { Home: 'home', Chats: 'chat', Nearby: 'radar', Security: 'shield', Settings: 'settings' };

function Tabs() {
  const t = useTheme();
  // The app draws edge-to-edge, so the tab bar must sit above the system navigation bar:
  // tiny with gesture navigation, ~48 dp with the 3-button bar.
  const bottom = useSafeAreaInsets().bottom;
  const unread = useMesh(s => s.conversations.reduce((n, c) => n + c.unread, 0) + s.invites.length);
  return (
    <Tab.Navigator screenOptions={({ route }) => ({
      headerShown: false,
      tabBarActiveTintColor: t.c.primary,
      tabBarInactiveTintColor: t.c.textFaint,
      tabBarStyle: { backgroundColor: t.c.surface, borderTopColor: t.c.border, height: 64 + bottom, paddingBottom: 8 + bottom, paddingTop: 6 },
      tabBarLabelStyle: { fontSize: 11, fontWeight: '600' },
      tabBarIcon: ({ color }) => <Icon name={tabIcons[route.name]} color={color} size={22} />,
    })}>
      <Tab.Screen name="Home" component={HomeScreen} />
      <Tab.Screen name="Chats" component={ChatsScreen} options={{ tabBarBadge: unread || undefined }} />
      <Tab.Screen name="Nearby" component={NearbyScreen} />
      <Tab.Screen name="Security" component={SecurityCenterScreen} />
      <Tab.Screen name="Settings" component={SettingsScreen} />
    </Tab.Navigator>
  );
}

/** Last line of defence: a render error in any screen shows a recovery view instead of closing the app. */
class ErrorBoundary extends React.Component<{ children: React.ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null };
  static getDerivedStateFromError(error: Error) { return { error }; }
  componentDidCatch(error: Error) { console.warn('[OfflineMesh] screen error', error?.message); }
  render() {
    if (!this.state.error) return this.props.children;
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#0B0E14', padding: 24 }}>
        <Text style={{ color: '#FFFFFF', fontSize: 20, fontWeight: '700' }}>Something went wrong</Text>
        <Text style={{ color: '#9AA4B2', textAlign: 'center', marginTop: 8 }}>{String(this.state.error?.message ?? '')}</Text>
        <Pressable onPress={() => this.setState({ error: null })}
          style={{ marginTop: 20, backgroundColor: '#2F6BFF', paddingHorizontal: 22, paddingVertical: 12, borderRadius: 12 }}>
          <Text style={{ color: '#FFFFFF', fontWeight: '600' }}>Try again</Text>
        </Pressable>
      </View>
    );
  }
}

export default function App() {
  return <ErrorBoundary><AppRoot /></ErrorBoundary>;
}

function AppRoot() {
  const t = useTheme();
  const { ready, state, error, refreshState, refreshLists, refreshNearby } = useMesh();
  const onboarding = useOnboarding(s => s.active);

  useEffect(() => {
    if (!Mesh) return;
    refreshState().then(() => { refreshLists(); refreshNearby(); });
    const stop = startMeshSync();
    Mesh.setAppForeground(true);
    const sub = AppState.addEventListener('change', s => {
      Mesh.setAppForeground(s === 'active');
      if (s === 'active') { refreshState(); refreshLists(); }
    });
    return () => { stop(); sub.remove(); };
  }, [refreshState, refreshLists, refreshNearby]);

  // Resume the background service automatically if the user allowed it before.
  useEffect(() => {
    if (state?.hasIdentity && state.permissionsGranted && !state.serviceRunning && !onboarding) Mesh.startService().catch(() => {});
  }, [state?.hasIdentity, state?.permissionsGranted]); // eslint-disable-line react-hooks/exhaustive-deps

  const baseNav = t.dark ? DarkTheme : DefaultTheme;
  const navTheme = { ...baseNav, colors: { ...baseNav.colors, background: t.c.bg, card: t.c.surface, text: t.c.text, border: t.c.border, primary: t.c.primary } };

  if (!Mesh) {
    return <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: t.c.bg }}><Text style={{ color: t.c.text }}>Native module missing</Text></View>;
  }
  if (error) {
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: t.c.bg, padding: 24 }}>
        <Icon name="alert" color={t.c.danger} size={40} />
        <Text style={[t.font.h2, { color: t.c.text, marginTop: 12 }]}>Secure storage error</Text>
        <Text style={{ color: t.c.textDim, textAlign: 'center', marginTop: 8 }}>{error}</Text>
      </View>
    );
  }

  return (
    <SafeAreaProvider>
      <NavigationContainer theme={navTheme}>
        {!ready || !state ? <SplashScreen /> : (
          <Stack.Navigator screenOptions={{ headerShown: false, animation: 'slide_from_right', contentStyle: { backgroundColor: t.c.bg } }}>
            {!state.hasIdentity || onboarding ? (
              <>
                {!state.hasIdentity ? (
                  <>
                    <Stack.Screen name="Welcome" component={WelcomeScreen} />
                    <Stack.Screen name="Onboarding" component={OnboardingScreen} />
                    <Stack.Screen name="CreateProfile" component={CreateProfileScreen} />
                  </>
                ) : null}
                <Stack.Screen name="SecuritySetup" component={SecuritySetupScreen} />
              </>
            ) : (
              <>
                <Stack.Screen name="Tabs" component={Tabs} />
                <Stack.Screen name="Chat" component={ChatScreen} />
                <Stack.Screen name="GroupChat" component={GroupChatScreen} />
                <Stack.Screen name="CreateGroup" component={CreateGroupScreen} />
                <Stack.Screen name="GroupDetails" component={GroupDetailsScreen} />
                <Stack.Screen name="UserProfile" component={UserProfileScreen} />
                <Stack.Screen name="MyQr" component={MyQrScreen} />
                <Stack.Screen name="QrScanner" component={QrScannerScreen} />
                <Stack.Screen name="MeshDashboard" component={MeshDashboardScreen} />
                <Stack.Screen name="SecurityCenter" component={SecurityCenterScreen} />
                <Stack.Screen name="VerifiedDevices" component={VerifiedDevicesScreen} />
                <Stack.Screen name="Storage" component={StorageScreen} />
                <Stack.Screen name="Privacy" component={PrivacyScreen} />
                <Stack.Screen name="Troubleshooting" component={TroubleshootingScreen} />
              </>
            )}
          </Stack.Navigator>
        )}
      </NavigationContainer>
    </SafeAreaProvider>
  );
}
