import React from 'react';
import { Text } from 'react-native';
import { NavigationContainer, DefaultTheme, DarkTheme } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { useColorScheme } from 'react-native';
import VerdictScreen from '../screens/VerdictScreen';
import CameraScreen from '../screens/CameraScreen';
import NearbyScreen from '../screens/NearbyScreen';
import ReportScreen from '../screens/ReportScreen';
import { useTheme } from '../theme/colors';
import { RootStackParamList, TabParamList } from './types';

const Tab = createBottomTabNavigator<TabParamList>();
const Stack = createNativeStackNavigator<RootStackParamList>();

const TAB_ICONS: Record<keyof TabParamList, string> = {
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
      <Tab.Screen name="Check" component={VerdictScreen} />
      <Tab.Screen name="Scan" component={CameraScreen} />
      <Tab.Screen name="Nearby" component={NearbyScreen} />
    </Tab.Navigator>
  );
}

export default function RootNavigator() {
  const scheme = useColorScheme();
  const theme = useTheme();
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

  return (
    <NavigationContainer theme={navTheme}>
      <Stack.Navigator screenOptions={{ headerShown: false }}>
        <Stack.Screen name="Tabs" component={Tabs} />
        <Stack.Screen name="ReportSign" component={ReportScreen} options={{ presentation: 'modal' }} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}
