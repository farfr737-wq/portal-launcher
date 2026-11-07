package com.portal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portal.core.PortalPaths
import com.portal.settings.SettingsStore
import com.portal.skins.LocalSkin
import com.portal.skins.SkinManager
import com.portal.skins.SkinModel
import com.portal.skins.SkinValidator
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

fun pickFile(title: String, ext: String): Path? {
    val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
    d.setFilenameFilter { _, name -> name.lowercase().endsWith(ext) }
    d.isVisible = true
    val f = d.file ?: return null
    return Path.of(d.directory, f)
}

@Composable
fun SkinsPage(vm: AppViewModel) {
    val settings by SettingsStore.state.collectAsState()
    var skins by remember { mutableStateOf(SkinManager.list()) }
    var current by remember { mutableStateOf<LocalSkin?>(skins.firstOrNull { it.id == settings.selectedSkinId } ?: skins.firstOrNull()) }
    var model by remember { mutableStateOf(current?.model ?: SkinModel.CLASSIC) }
    var pending by remember { mutableStateOf<Path?>(null) }   // новый, ещё не сохранённый файл
    var pendingName by remember { mutableStateOf("") }
    var recentVersion by remember { mutableStateOf(0) }

    val previewFile = pending ?: current?.file
    val image = rememberSkinImage(previewFile)

    fun refresh(selectId: String? = null) {
        skins = SkinManager.list()
        current = skins.firstOrNull { it.id == (selectId ?: current?.id) } ?: skins.firstOrNull()
        recentVersion++
    }

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(0.5f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            H1("Скины")
            SkinViewerCard(image, model == SkinModel.SLIM, Modifier.weight(1f).fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkinModel.entries.forEach { m ->
                    Chip(m.title, model == m, {
                        model = m
                        if (pending == null) current?.let { SkinManager.setModel(it.id, m); refresh() }
                    })
                }
            }
        }
        Column(Modifier.weight(0.5f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                H2("Загрузить свой скин")
                Muted("PNG 64×64 (поддерживается и старый 64×32). Скины хранятся в ${PortalPaths.skinsLocal} и не теряются при обновлении лаунчера.", size = 12)
                if (pending == null) {
                    PrimaryButton("Выбрать PNG…", {
                        val f = pickFile("Выберите скин (PNG)", ".png")
                        if (f != null) {
                            try {
                                SkinValidator.validate(java.nio.file.Files.readAllBytes(f))
                                pending = f; pendingName = f.fileName.toString().removeSuffix(".png")
                                val img = SkinValidator.validate(java.nio.file.Files.readAllBytes(f))
                                model = if (SkinValidator.looksSlim(img)) SkinModel.SLIM else SkinModel.CLASSIC
                            } catch (e: Throwable) { vm.showError(e, "Скин не подходит") }
                        }
                    })
                } else {
                    PField(pendingName, { pendingName = it }, "Название скина", Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Сохранить", {
                            try {
                                val s = SkinManager.import(pending!!, pendingName, model)
                                pending = null; refresh(s.id); vm.toast("Скин сохранён")
                            } catch (e: Throwable) { vm.showError(e) }
                        })
                        GhostButton("Отмена", { pending = null; model = current?.model ?: SkinModel.CLASSIC })
                    }
                }
            }

            current?.let { cur ->
                Panel(Modifier.fillMaxWidth()) {
                    H2("Выбранный скин: ${cur.name}")
                    val isUsed = settings.selectedSkinId == cur.id
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(if (isUsed) "Используется" else "Использовать", {
                            SettingsStore.update { it.copy(selectedSkinId = cur.id) }
                            SkinManager.markUsed(cur.id); refresh(); vm.toast("Скин выбран")
                        }, enabled = !isUsed)
                        GhostButton("Удалить", { SkinManager.delete(cur); refresh(); }, danger = true)
                    }
                }
            }

            Panel(Modifier.fillMaxWidth()) {
                H2("Мои скины (${skins.size})")
                if (skins.isEmpty()) Muted("Пока пусто.", size = 12)
                skins.forEach { s ->
                    val img = rememberSkinImage(s)
                    Row(
                        Modifier.fillMaxWidth().clickable { pending = null; current = s; model = s.model }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SkinHead(img, 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = if (s.id == current?.id) PC.accent else PC.text, fontSize = 14.sp)
                            Muted(s.model.title, size = 11)
                        }
                        if (s.id == settings.selectedSkinId) Badge("активен", PC.accent)
                    }
                }
            }

            Panel(Modifier.fillMaxWidth()) {
                H2("Недавно использованные")
                val recent = remember(recentVersion, skins) { SkinManager.recent() }
                if (recent.isEmpty()) Muted("Здесь появятся скины, которые вы выбирали.", size = 12)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { s -> Column(Modifier.clickable { pending = null; current = s; model = s.model }) { SkinHead(rememberSkinImage(s), 48.dp) } }
                }
            }

            Panel(Modifier.fillMaxWidth(), PC.panel2) {
                H2("Локальный и серверный скин")
                Muted(
                    "Локальный скин показывается внутри Portal Launcher. В игре он виден только там, где это поддерживают система авторизации и сервер " +
                        "(например, серверы Portal/сторонние skin-системы). Официальный скин Minecraft меняется только на minecraft.net после входа через Microsoft — " +
                        "лаунчер его не подменяет.", size = 12
                )
            }
        }
    }
}
