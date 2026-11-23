package com.aibuilt.addanewprofessionaltabtoarinca.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aibuilt.addanewprofessionaltabtoarinca.BuildConfig

@Composable
fun ArinChatsScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Chats", style = MaterialTheme.typography.titleLarge)
        Text(
            "Arin's chat workspace lives here. The APK Bypass tab sits beside it in the side menu.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun ProjectsScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Projects", style = MaterialTheme.typography.titleLarge)
        Text(
            "Bypass projects are stored on disk under the APK Bypass tab: pick it in the menu to create, resume or delete one.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun SettingsScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ListItem(
            headlineContent = { Text("Advanced I/O") },
            supportingContent = { Text("APK Bypass needs file access to read APKs and install clones.") }
        )
        ListItem(
            headlineContent = { Text("Version") },
            supportingContent = { Text(BuildConfig.VERSION_NAME) }
        )
    }
}
