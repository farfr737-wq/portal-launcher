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
import androidx.compose.ui.geometry.Offset
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
    val st by SettingsStore.state.collectAsState()
    Box(Modifier.fillMaxSize().background(PC.bg)) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(PC.accent.copy(alpha = 0.07f), Color.Transparent), Offset(1500f, 120f), 900f)))
        if (st.wallpaperEverywhere) Assets.wallpaper(st)?.let { wp ->
            Image(wp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(PC.bg.copy(alpha = st.wallpaperDim / 100f)))
        }
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
                            Page.CONTENT -> ContentPage(vm)
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
    val st by SettingsStore.state.collectAsState()
    Column(
        Modifier.width(222.dp).fillMaxHeight()
            .background(Brush.verticalGradient(listOf(PC.panel, PC.panel.copy(alpha = 0.94f))))
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.padding(6.dp, 4.dp, 6.dp, 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(Assets.icon(st), "Portal", Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("PORTAL", color = PC.text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
                Text("LAUNCHER", color = PC.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            }
        }
        for (p in listOf(Page.HOME, Page.VERSIONS, Page.CONTENT, Page.SKINS, Page.COMMUNITY, Page.SETTINGS)) NavItem(p, vm.page == p) { vm.page = p }
        Spacer(Modifier.weight(1f))
        NavItem(Page.LOGS, vm.page == Page.LOGS) { vm.page = Page.LOGS }
    }
}

@Composable
private fun NavItem(page: Page, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (active) Brush.horizontalGradient(listOf(PC.accent.copy(alpha = 0.20f), PC.accent.copy(alpha = 0.04f))) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavGlyph(page, if (active) PC.accent else PC.muted)
        Spacer(Modifier.width(12.dp))
        Text(tr(page.title), color = if (active) PC.text else PC.muted, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (active) Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(PC.accent))
    }
}

// ---------------------------------------------------------------- Главная

@Composable
private fun HomePage(vm: AppViewModel) {
    val cfg = vm.selected
    val hs by SettingsStore.state.collectAsState()
    val bubble = when (vm.gameState) {
        GameState.IDLE -> if (cfg == null) "Давай поставим первую версию!" else "Портал готов. Погнали?"
        GameState.PREPARING -> "Открываю портал…"
        GameState.RUNNING -> "Удачной игры!"
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Добро пожаловать")
                H1("Главная")
            }
            if (vm.instances.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    vm.instances.take(5).forEach { i -> Chip("${i.mcVersion} ${i.loaderTitle}", i.id == cfg?.id, { vm.select(i.id) }) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(26.dp)).glowBorder(26.dp, 0.55f)) {
            Image(Assets.wallpaper(hs) ?: Images.background, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(PC.bg.copy(alpha = 0.92f), PC.bg.copy(alpha = 0.35f), Color.Transparent))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, PC.bg.copy(alpha = 0.75f)))))

            Column(Modifier.align(Alignment.BottomStart).padding(32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cfg == null) {
                    SectionLabel("Старт")
                    Text(tr("Версия не выбрана"), color = PC.text, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                    Muted("Установите Minecraft на странице «Версии»", size = 15)
                    Spacer(Modifier.height(12.dp))
                    PlayPill("Установить версию", true, { vm.page = Page.VERSIONS })
                } else {
                    SectionLabel("Minecraft Java Edition")
                    Text(cfg.mcVersion, color = PC.text, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge(cfg.loaderTitle + if (cfg.loaderVersion.isNotBlank()) " ${cfg.loaderVersion}" else "", PC.accent)
                        Muted("   Java ${cfg.javaMajor}  •  ${tr("Моды")}: ${cfg.mods.size}", size = 13)
                    }
                    Spacer(Modifier.height(14.dp))
                    PlayButton(vm)
                }
            }

            Row(Modifier.align(Alignment.BottomEnd).padding(28.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SpeechBubble(bubble, Modifier.padding(bottom = 70.dp))
                MascotPortal(210.dp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickCard("Контент", "Моды, шейдеры, сборки", Page.CONTENT, vm, Modifier.weight(1f))
            QuickCard("Скины", "Образ вашего героя", Page.SKINS, vm, Modifier.weight(1f))
            QuickCard("Сообщество", "Игроки и серверы Portal", Page.COMMUNITY, vm, Modifier.weight(1f))
        }
    }
}

@Composable
private fun QuickCard(title: String, sub: String, page: Page, vm: AppViewModel, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(18.dp)).background(PC.panel).glowBorder(18.dp, 0.25f)
            .clickable { vm.page = page }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(PC.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            NavGlyph(page, PC.accent, 22.dp)
        }
        Column {
            Text(tr(title), color = PC.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Muted(sub, size = 12)
        }
    }
}

@Composable
private fun PlayButton(vm: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val label = when (vm.gameState) {
                GameState.IDLE -> tr("ИГРАТЬ")
                GameState.PREPARING -> tr("ПОДГОТОВКА…")
                GameState.RUNNING -> tr("ИГРА ЗАПУЩЕНА")
            }
            PlayPill(label, vm.gameState == GameState.IDLE, { vm.play() }, Modifier.width(280.dp))
            if (vm.gameState != GameState.IDLE) Text(tr(vm.gameStatus), color = PC.text, fontSize = 14.sp)
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
                else Text(tr("Нет профиля"), color = PC.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
    Box(Modifier.fillMaxSize().background(Color(0xCC050810)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(500.dp).clip(RoundedCornerShape(24.dp)).background(PC.panel).glowBorder(24.dp, 0.6f).padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MascotPortal(130.dp)
            H2(b.title)
            Text(tr(b.message.ifBlank { "Подготовка…" }), color = PC.muted, fontSize = 13.sp, maxLines = 2)
            ProgressLine(b.fraction)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(b.fraction?.let { "${(it * 100).toInt()}%" } ?: "", color = PC.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
            if (e.action != null) TextButton({ vm.error = null }) { Text(tr("Закрыть"), color = PC.muted) }
            else TextButton({ vm.error = null; vm.page = Page.LOGS }) { Text(tr("Открыть логи"), color = PC.muted) }
        },
    )
}

@Composable
private fun Toast(vm: AppViewModel) {
    val t = vm.toast ?: return
    LaunchedEffect(t) { delay(3500); if (vm.toast == t) vm.toast = null }
    Box(Modifier.fillMaxSize().padding(bottom = 86.dp), contentAlignment = Alignment.BottomCenter) {
        Row(Modifier.clip(RoundedCornerShape(16.dp)).background(PC.panel2).glowBorder(16.dp, 0.6f).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(Images.mascot, null, Modifier.size(26.dp), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
            Text(t, color = PC.text, fontSize = 14.sp)
        }
    }
}
