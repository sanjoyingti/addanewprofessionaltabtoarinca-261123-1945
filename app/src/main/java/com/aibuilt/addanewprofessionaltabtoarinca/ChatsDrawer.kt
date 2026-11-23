package com.aibuilt.addanewprofessionaltabtoarinca

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatsDrawer(
    current: ArinScreen,
    onChats: () -> Unit,
    onProjects: () -> Unit,
    onBypass: () -> Unit,
    onSettings: () -> Unit
) {
    Column(Modifier.padding(12.dp)) {
        Text("Arin", style = MaterialTheme.typography.headlineSmall)
        Text("Professional workspace", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        NavigationDrawerItem(
            label = { Text(ArinScreen.Chats.label) },
            icon = { Icon(ArinScreen.Chats.icon, contentDescription = null) },
            selected = current == ArinScreen.Chats,
            onClick = onChats
        )
        NavigationDrawerItem(
            label = { Text(ArinScreen.Projects.label) },
            icon = { Icon(ArinScreen.Projects.icon, contentDescription = null) },
            selected = current == ArinScreen.Projects,
            onClick = onProjects
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Professional tools", style = MaterialTheme.typography.labelMedium)
        NavigationDrawerItem(
            label = {
                Column {
                    Text(ArinScreen.Bypass.label)
                    Text(
                        ArinScreen.Bypass.subtitle ?: "",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            icon = { Icon(ArinScreen.Bypass.icon, contentDescription = null) },
            selected = current == ArinScreen.Bypass,
            onClick = onBypass
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        NavigationDrawerItem(
            label = { Text(ArinScreen.Settings.label) },
            icon = { Icon(ArinScreen.Settings.icon, contentDescription = null) },
            selected = current == ArinScreen.Settings,
            onClick = onSettings
        )
    }
}
