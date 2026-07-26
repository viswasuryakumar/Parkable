import React from 'react';
import { Text } from 'react-native';
import { NavigationContainer, DefaultTheme, DarkTheme } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { useColorScheme } from 'react-native';
import HomeScreen from '../screens/HomeScreen';
import VerdictScreen from '../screens/VerdictScreen';
import CameraScreen from '../screens/CameraScreen';
import NearbyScreen from '../screens/NearbyScreen';
import ReportScreen from '../screens/ReportScreen';
import HistoryScreen from '../screens/HistoryScreen';
import FavoritesScreen from '../screens/FavoritesScreen';
import FindMyCarScreen from '../screens/FindMyCarScreen';
import OnboardingScreen from '../screens/OnboardingScreen';
import { useTheme } from '../theme/colors';
import { hasSeenOnboarding } from '../utils/onboarding';
import { RootStackParamList, TabParamList } from './types';

const Tab = createBottomTabNavigator<TabParamList>();
const Stack = createNativeStackNavigator<RootStackParamList>();

const TAB_ICONS: Record<keyof TabParamList, string> = {
  Home: '🏠',
  Check: '🅿️',
  Scan: '📷',
  Nearby: '📍',
};

function Tabs() {
  const theme = useTheme();
  return (
    <Tab.Navigator
      screenOptions={({ route }) => ({
        headerShown: false,
        tabBarActiveTintColor: theme.accent,
        tabBarInactiveTintColor: theme.textMuted,
        tabBarStyle: { backgroundColor: theme.background, borderTopColor: theme.border },
        tabBarIcon: () => (
          <Text style={{ fontSize: 20 }}>{TAB_ICONS[route.name as keyof TabParamList]}</Text>
        ),
      })}
    >
      <Tab.Screen name="Home" component={HomeScreen} />
      <Tab.Screen name="Check" component={VerdictScreen} />
      <Tab.Screen name="Scan" component={CameraScreen} />
      <Tab.Screen name="Nearby" component={NearbyScreen} />
    </Tab.Navigator>
  );
}

export default function RootNavigator() {
  const scheme = useColorScheme();
  const theme = useTheme();
  const [initialRoute, setInitialRoute] = React.useState<'Onboarding' | 'Tabs' | null>(null);

  React.useEffect(() => {
    hasSeenOnboarding().then((seen) => setInitialRoute(seen ? 'Tabs' : 'Onboarding'));
  }, []);

  const navTheme = {
    ...(scheme === 'dark' ? DarkTheme : DefaultTheme),
    colors: {
      ...(scheme === 'dark' ? DarkTheme.colors : DefaultTheme.colors),
      background: theme.background,
      card: theme.background,
      border: theme.border,
      text: theme.text,
      primary: theme.accent,
    },
  };

  // Briefly blank while the onboarding flag loads from storage, rather than
  // flashing Tabs and then jumping to Onboarding a frame later.
  if (initialRoute === null) {
    return null;
  }

  return (
    <NavigationContainer theme={navTheme}>
      <Stack.Navigator screenOptions={{ headerShown: false }} initialRouteName={initialRoute}>
        <Stack.Screen name="Onboarding" component={OnboardingScreen} />
        <Stack.Screen name="Tabs" component={Tabs} />
        <Stack.Screen name="ReportSign" component={ReportScreen} options={{ presentation: 'modal' }} />
        <Stack.Screen name="History" component={HistoryScreen} options={{ headerShown: true, title: 'History' }} />
        <Stack.Screen name="Favorites" component={FavoritesScreen} options={{ headerShown: true, title: 'Favorites' }} />
        <Stack.Screen name="FindMyCar" component={FindMyCarScreen} options={{ headerShown: true, title: 'Find My Car' }} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}
