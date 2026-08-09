package com.xai.grokremote.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.xai.grokremote.ui.screens.ChatScreen
import com.xai.grokremote.ui.screens.PairScreen
import com.xai.grokremote.ui.theme.Bg

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun GrokRemoteRoot(
    initialPairUri: String? = null,
    vm: GrokViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(initialPairUri) {
        vm.applyPairUri(initialPairUri)
    }

    LaunchedEffect(state.errorBanner) {
        val msg = state.errorBanner ?: return@LaunchedEffect
        snack.showSnackbar(msg)
        vm.dismissError()
    }

    val perms = rememberMultiplePermissionsState(
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
    )
    LaunchedEffect(Unit) {
        if (!perms.allPermissionsGranted) {
            perms.launchMultiplePermissionRequest()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
        snackbarHost = {
            SnackbarHost(snack) { data ->
                Snackbar(
                    action = {
                        TextButton(onClick = { data.dismiss() }) { Text("OK") }
                    },
                ) { Text(data.visuals.message) }
            }
        },
        containerColor = Bg,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.needsPairing) {
                PairScreen(
                    onPaired = { base, token -> vm.savePairing(base, token) },
                )
            } else {
                ChatScreen(state = state, vm = vm)
            }
        }
    }
}
