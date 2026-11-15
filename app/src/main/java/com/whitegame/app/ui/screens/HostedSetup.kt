package com.whitegame.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import com.whitegame.app.ui.components.Field
import com.whitegame.app.ui.components.Notice
import com.whitegame.app.ui.components.SecondaryButton
import com.whitegame.app.ui.components.SectionHeader
import com.whitegame.app.ui.components.VSpace
import com.whitegame.app.ui.theme.Space
import com.whitegame.app.viewmodel.HostedSetupViewModel

@Composable
fun HostedSetup(vm: HostedSetupViewModel = hiltViewModel()) {
    var expanded by remember { mutableStateOf(false) }
    SecondaryButton("Self-hosted WireGuard setup", onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth())
    if (!expanded) return
    Column {
        VSpace(Space.md)
        SectionHeader("Your server", "Create a profile on your WireGuard host")
        VSpace(Space.sm)
        Notice("Enter the provisioning URL and enrollment token supplied by your administrator. Your private key stays on this phone. Retrying or refreshing uses the same device key.")
        VSpace(Space.sm)
        Field("Server HTTPS URL", vm.serverUrl, vm::updateServer,
            placeholder = "https://provision.example.com", mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth())
        VSpace(Space.sm)
        Field("Enrollment token", vm.token, vm::updateToken,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(), enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth())
        VSpace(Space.md)
        SecondaryButton(if (vm.busy) "Enrolling…" else "Create / refresh profile", onClick = vm::enroll,
            enabled = !vm.busy && vm.serverUrl.isNotBlank() && vm.token.isNotBlank(), loading = vm.busy,
            modifier = Modifier.fillMaxWidth())
        if (vm.message.isNotBlank()) { VSpace(Space.sm); Notice(vm.message, emphasis = true) }
    }
}
