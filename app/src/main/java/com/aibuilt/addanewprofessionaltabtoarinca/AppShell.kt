package com.aibuilt.addanewprofessionaltabtoarinca

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aibuilt.addanewprofessionaltabtoarinca.bypass.BypassScreen
import com.aibuilt.addanewprofessionaltabtoarinca.ui.ArinChatsScreen
import com.aibuilt.addanewprofessionaltabtoarinca.ui.ProjectsScreen
import com.aibuilt.addanewprofessionaltabtoarinca.ui.SettingsScreen
import kotlinx.coroutines.launch

enum class ArinScreen(val route: String, val label: String, val subtitle: String?, val icon: ImageVector) {
    Chats("chats", "Chats", null, Icons.Filled.Forum),
    Projects("projects", "Projects", null, Icons.Filled.Folder),
    Bypass("bypass", "APK Bypass", "Server Killer", Icons.Filled.Shield),
    Settings("settings", "Settings", null, Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell() {
    val nav: NavHostController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: ArinScreen.Chats.route
    val current = ArinScreen.entries.firstOrNull { it.route == route } ?: ArinScreen.Chats

    fun go(screen: ArinScreen) {
        scope.launch { drawerState.close() }
        if (screen.route != route) nav.navigate(screen.route) {
            popUpTo(nav.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ChatsDrawer(
                    current = current,
                    onChats = { go(ArinScreen.Chats) },
                    onProjects = { go(ArinScreen.Projects) },
                    onBypass = { go(ArinScreen.Bypass) },
                    onSettings = { go(ArinScreen.Settings) }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Box {
                            Text(if (current.subtitle == null) current.label else "${current.label} · ${current.subtitle}")
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Forum, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NavHost(navController = nav, startDestination = ArinScreen.Chats.route) {
                    composable(ArinScreen.Chats.route) { ArinChatsScreen() }
                    composable(ArinScreen.Projects.route) { ProjectsScreen() }
                    composable(ArinScreen.Bypass.route) { BypassScreen() }
                    composable(ArinScreen.Settings.route) { SettingsScreen() }
                }
            }
        }
    }
}
