package com.portal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portal.accounts.AccountStore
import com.portal.settings.SettingsStore
import com.portal.skins.SkinManager
import kotlinx.coroutines.delay

@Composable
fun App(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().background(PC.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Sidebar(vm)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedContent(
                        targetState = vm.page,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        label = "page",
                    ) { p ->
                        when (p) {
                            Page.HOME -> HomePage(vm)
                            Page.VERSIONS -> VersionsPage(vm)
                            Page.SKINS -> SkinsPage(vm)
                            Page.COMMUNITY -> CommunityPage(vm)
                            Page.SETTINGS -> SettingsPage(vm)
                            Page.LOGS -> LogsPage(vm)
                        }
                    }
                }
            }
            BottomBar(vm)
        }
        BusyOverlay(vm)
        ErrorDialog(vm)
        Toast(vm)
    }
}

@Composable
private fun Sidebar(vm: AppViewModel) {
    Column(
        Modifier.width(210.dp).fillMaxHeight().background(PC.panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.padding(6.dp, 6.dp, 6.dp, 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(Images.logo, "Portal", Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(10.dp))
            Text("PORTAL", color = PC.text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
        }
        for (p in listOf(Page.HOME, Page.VERSIONS, Page.SKINS, Page.COMMUNITY, Page.SETTINGS)) NavItem(p.title, vm.page == p) { vm.page = p }
        Spacer(Modifier.weight(1f))
        NavItem(Page.LOGS.title, vm.page == Page.LOGS) { vm.page = Page.LOGS }
    }
}

@Composable
private fun NavItem(title: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (active) PC.accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(if (active) PC.accent else Color.Transparent))
        Spacer(Modifier.width(10.dp))
        Text(title, color = if (active) PC.accent else PC.text, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ---------------------------------------------------------------- Главная

@Composable
private fun HomePage(vm: AppViewModel) {
    val cfg = vm.selected
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        H1("Главная")
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp))) {
            Image(Images.background, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x330B0F14), Color(0xE60B0F14)))))
            Column(Modifier.align(Alignment.BottomStart).padding(28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (cfg == null) {
                    Text("Версия не выбрана", color = PC.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Muted("Установите Minecraft на странице «Версии»", size = 15)
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("Установить версию", { vm.page = Page.VERSIONS })
                } else {
                    Text("Minecraft ${cfg.mcVersion}", color = PC.text, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge(cfg.loaderTitle + if (cfg.loaderVersion.isNotBlank()) " ${cfg.loaderVersion}" else "", PC.accent)
                        Muted("   Java ${cfg.javaMajor}  •  модов: ${cfg.mods.size}", size = 13)
                    }
                    Spacer(Modifier.height(12.dp))
                    PlayButton(vm)
                }
            }
            if (vm.instances.size > 1) {
                Column(Modifier.align(Alignment.TopEnd).padding(16.dp).width(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Muted("Выбор версии", size = 12)
                    vm.instances.take(6).forEach { i ->
                        Chip("${i.mcVersion} ${i.loaderTitle}", i.id == cfg?.id, { vm.select(i.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayButton(vm: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val label = when (vm.gameState) {
                GameState.IDLE -> "ИГРАТЬ"
                GameState.PREPARING -> "ПОДГОТОВКА…"
                GameState.RUNNING -> "ИГРА ЗАПУЩЕНА"
            }
            PrimaryButton(label, { vm.play() }, Modifier.width(220.dp).height(52.dp), enabled = vm.gameState == GameState.IDLE)
            if (vm.gameState != GameState.IDLE) Text(vm.gameStatus, color = PC.text, fontSize = 14.sp)
        }
        if (vm.gameState == GameState.PREPARING) ProgressLine(vm.gameProgress, Modifier.width(360.dp))
    }
}

// ---------------------------------------------------------------- Нижняя панель

@Composable
private fun BottomBar(vm: AppViewModel) {
    val accounts by AccountStore.accounts.collectAsState()
    val settings by SettingsStore.state.collectAsState()
    val acc = accounts.firstOrNull { it.id == settings.activeAccountId } ?: accounts.firstOrNull()
    var showAccounts by remember { mutableStateOf(false) }
    val skin = remember(settings.selectedSkinId, vm.page) { SkinManager.list().firstOrNull { it.id == settings.selectedSkinId } }
    val skinImg = rememberSkinImage(skin)
    val cfg = vm.selected

    Row(
        Modifier.fillMaxWidth().background(PC.panel).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { showAccounts = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            SkinHead(skinImg, 38.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                if (acc != null) PortalNick(acc.username)
                else Text("Нет профиля", color = PC.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Muted(
                    when {
                        acc == null -> "Нажмите, чтобы добавить"
                        acc.isMicrosoft -> "Microsoft"
                        else -> "Локальный профиль (тест)"
                    }, size = 11
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Muted("Скин: ${skin?.name ?: "не выбран"}", size = 12)
        if (cfg != null) {
            GhostButton("Настройки запуска", { vm.page = Page.VERSIONS })
        }
    }
    if (showAccounts) AccountsDialog(vm) { showAccounts = false }
}

// ---------------------------------------------------------------- Диалоги

@Composable
private fun BusyOverlay(vm: AppViewModel) {
    val b = vm.busy ?: return
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
        Panel(Modifier.width(480.dp), PC.panel) {
            H2(b.title)
            Text(b.message.ifBlank { "Подготовка…" }, color = PC.muted, fontSize = 13.sp, maxLines = 2)
            ProgressLine(b.fraction)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Muted(b.fraction?.let { "${(it * 100).toInt()}%" } ?: "")
                GhostButton("Отменить", { vm.cancelBusy() }, danger = true)
            }
        }
    }
}

@Composable
private fun ErrorDialog(vm: AppViewModel) {
    val e = vm.error ?: return
    AlertDialog(
        onDismissRequest = { vm.error = null },
        containerColor = PC.panel,
        title = { Text(e.title, color = PC.danger, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = { if (e.hint != null) Text(e.hint, color = PC.text, fontSize = 13.sp) },
        confirmButton = {
            if (e.action != null) TextButton({ vm.runAction(e.action) }) { Text(e.action.label, color = PC.accent) }
            else TextButton({ vm.error = null }) { Text("OK", color = PC.accent) }
        },
        dismissButton = {
            if (e.action != null) TextButton({ vm.error = null }) { Text("Закрыть", color = PC.muted) }
            else TextButton({ vm.error = null; vm.page = Page.LOGS }) { Text("Открыть логи", color = PC.muted) }
        },
    )
}

@Composable
private fun Toast(vm: AppViewModel) {
    val t = vm.toast ?: return
    LaunchedEffect(t) { delay(3500); if (vm.toast == t) vm.toast = null }
    Box(Modifier.fillMaxSize().padding(bottom = 86.dp), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(PC.panel2).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(t, color = PC.accent, fontSize = 14.sp)
        }
    }
}
