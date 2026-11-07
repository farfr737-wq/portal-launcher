package com.portal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portal.accounts.AccountStore
import com.portal.accounts.DeviceCode
import com.portal.accounts.MicrosoftAuth
import com.portal.settings.SettingsStore
import kotlinx.coroutines.launch

@Composable
fun AccountsDialog(vm: AppViewModel, onClose: () -> Unit) {
    val accounts by AccountStore.accounts.collectAsState()
    val settings by SettingsStore.state.collectAsState()
    var nick by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var msBusy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = PC.panel,
        title = { Text("Профили", color = PC.text, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (accounts.isEmpty()) Muted("Профилей пока нет. Добавьте профиль ниже.")
                accounts.forEach { a ->
                    val active = a.id == (settings.activeAccountId.ifBlank { accounts.first().id })
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (active) PC.accent.copy(alpha = 0.14f) else PC.panel2)
                            .clickable { AccountStore.setActive(a.id) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            PortalNick(a.username)
                            Muted(if (a.isMicrosoft) "Microsoft — лицензионный аккаунт" else "Локальный профиль — только для тестов, не лицензия", size = 11)
                        }
                        TextButton({ AccountStore.remove(a.id) }) { Text("Удалить", color = PC.danger, fontSize = 12.sp) }
                    }
                }
                H2("Локальный профиль")
                Muted("Для тестов и одиночной игры. Не работает на серверах с проверкой лицензии.", size = 12)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PField(nick, { nick = it }, "Никнейм (3–16 символов)", Modifier.weight(1f))
                    PrimaryButton("Добавить", {
                        try { AccountStore.addLocal(nick.trim()); nick = "" } catch (e: Throwable) { vm.showError(e) }
                    })
                }
                H2("Microsoft")
                val c = code
                if (c == null) {
                    PrimaryButton(if (msBusy) "Подождите…" else "Войти через Microsoft", {
                        msBusy = true
                        vm.scope.launch {
                            try {
                                val dc = MicrosoftAuth.start()
                                code = dc
                                try { java.awt.Desktop.getDesktop().browse(java.net.URI(dc.verificationUri)) } catch (_: Exception) {}
                                val r = MicrosoftAuth.awaitLogin(dc)
                                AccountStore.addMicrosoft(r.username, r.uuid, r.refreshToken)
                                vm.toast("Вход выполнен: ${r.username}")
                            } catch (e: Throwable) {
                                vm.showError(e, "Вход через Microsoft не выполнен")
                            } finally { code = null; msBusy = false }
                        }
                    }, enabled = !msBusy)
                } else {
                    Muted("Откройте ${c.verificationUri} и введите код:")
                    Text(c.userCode, color = PC.accent, fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, letterSpacing = 4.sp)
                    Muted("Ожидание подтверждения…", size = 12)
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Закрыть", color = PC.accent) } },
    )
}
