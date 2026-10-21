package io.github.andru1989.xmaudiostudio.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andru1989.xmaudiostudio.R
import io.github.andru1989.xmaudiostudio.ui.dashboard.DashboardScreen
import io.github.andru1989.xmaudiostudio.ui.equalizer.EqualizerScreen
import io.github.andru1989.xmaudiostudio.ui.settings.SettingsScreen

@Composable
fun AppScaffold(vm: MainViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshDevices()
        vm.connect()
    }
    val requestPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    val importedTemplate = stringResource(R.string.imported_n)
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val text = if (message.startsWith("imported:")) importedTemplate.format(message.removePrefix("imported:").toInt()) else message
        snackbar.showSnackbar(text)
        vm.consumeMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Headphones, null) }, label = { Text(stringResource(R.string.tab_home)) })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.GraphicEq, null) }, label = { Text(stringResource(R.string.tab_eq)) })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text(stringResource(R.string.tab_settings)) })
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            0 -> DashboardScreen(state, vm, requestPermission, openEqualizer = { tab = 1 }, openSettings = { tab = 2 }, modifier = modifier)
            1 -> EqualizerScreen(state, vm, modifier)
            else -> SettingsScreen(state, vm, requestPermission, modifier)
        }
    }
}
