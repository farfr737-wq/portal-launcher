package com.portal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portal.accounts.AccountStore
import com.portal.settings.SettingsStore
import com.portal.skins.SkinManager
import com.portal.skins.SkinValidator
import com.portal.social.Favorite
import com.portal.social.PortalUser
import com.portal.social.SocialClient
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun CommunityPage(vm: AppViewModel) {
    val settings by SettingsStore.state.collectAsState()
    var urlInput by remember { mutableStateOf(settings.socialServerUrl) }
    var me by remember { mutableStateOf<PortalUser?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PortalUser>>(emptyList()) }
    var opened by remember { mutableStateOf<PortalUser?>(null) }
    var favorites by remember { mutableStateOf<List<Favorite>>(emptyList()) }
    var regNick by remember { mutableStateOf(AccountStore.active?.username ?: "") }
    var online by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(settings.socialServerUrl, settings.portalUserId) {
        favorites = SocialClient.favorites()
        if (SocialClient.isConfigured) {
            online = try { SocialClient.ping() } catch (_: Throwable) { false }
            if (online == true && SocialClient.isRegistered) me = try { SocialClient.me() } catch (e: Throwable) { vm.showError(e); null }
        }
    }

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(0.5f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            H1("Сообщество")

            Panel(Modifier.fillMaxWidth()) {
                H2("Сервер Portal")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PField(urlInput, { urlInput = it }, "Адрес сервера (http://host:8080)", Modifier.weight(1f))
                    PrimaryButton("Подключить", {
                        SettingsStore.update { it.copy(socialServerUrl = urlInput.trim()) }
                        vm.scope.launch { online = try { SocialClient.ping() } catch (e: Throwable) { vm.showError(e); false } }
                    })
                }
                Muted(
                    when (online) { true -> "● Сервер доступен"; false -> "● Сервер недоступен"; null -> "Сервер не подключён. Запуск своего сервера: см. README, раздел «Сервер Portal»." },
                    size = 12
                )
            }

            if (SocialClient.isConfigured && online == true) {
                Panel(Modifier.fillMaxWidth()) {
                    H2("Мой профиль Portal")
                    val u = me
                    if (u == null) {
                        Muted("Вы ещё не зарегистрированы.", size = 12)
                        PField(regNick, { regNick = it }, "Никнейм (3–16: латиница, цифры, _)", Modifier.fillMaxWidth())
                        PrimaryButton("Зарегистрироваться", {
                            vm.scope.launch {
                                try {
                                    val model = SkinManager.list().firstOrNull { it.id == settings.selectedSkinId }?.model?.id ?: "classic"
                                    me = SocialClient.register(regNick.trim(), model); vm.toast("Профиль создан")
                                } catch (e: Throwable) { vm.showError(e, "Регистрация не удалась") }
                            }
                        })
                    } else {
                        ProfileCard(u, vm, favoriteToggle = false) {}
                        val skin = SkinManager.list().firstOrNull { it.id == settings.selectedSkinId }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("Опубликовать выбранный скин", {
                                if (skin == null) vm.toast("Сначала выберите скин на странице «Скины»") else vm.scope.launch {
                                    try {
                                        SocialClient.setModel(skin.model.id)
                                        me = SocialClient.uploadSkin(Files.readAllBytes(skin.file)); vm.toast("Скин опубликован")
                                    } catch (e: Throwable) { vm.showError(e, "Не удалось опубликовать скин") }
                                }
                            })
                            GhostButton("Выйти из Portal", { SocialClient.forgetAccount(); me = null }, danger = true)
                        }
                        Muted("Статус (${u.portalStatus.title}) назначается сервером и не редактируется локально.", size = 11)
                    }
                }

                Panel(Modifier.fillMaxWidth()) {
                    H2("Поиск игроков")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PField(query, { query = it }, "Никнейм", Modifier.weight(1f))
                        PrimaryButton("Найти", {
                            vm.scope.launch { try { results = SocialClient.search(query.trim()) } catch (e: Throwable) { vm.showError(e) } }
                        })
                    }
                    results.forEach { u -> UserRow(u) { opened = u } }
                }
            }

            Panel(Modifier.fillMaxWidth()) {
                H2("Избранное (${favorites.size})")
                if (favorites.isEmpty()) Muted("Добавляйте игроков в избранное на их профиле.", size = 12)
                favorites.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable {
                        vm.scope.launch { try { opened = SocialClient.user(f.userId) } catch (e: Throwable) { vm.showError(e) } }
                    }.padding(vertical = 4.dp)) { PortalNick(f.nickname) }
                }
            }
        }

        Column(Modifier.weight(0.5f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val u = opened
            if (u == null) {
                Panel(Modifier.fillMaxWidth()) { H2("Профиль"); Muted("Выберите игрока из поиска или избранного, чтобы увидеть его профиль и 3D-скин.") }
            } else {
                OtherProfile(u, vm) { favorites = SocialClient.favorites() }
            }
        }
    }
}

@Composable
private fun UserRow(u: PortalUser, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        PortalNick(u.nickname, u.portalStatus)
    }
}

@Composable
private fun ProfileCard(u: PortalUser, vm: AppViewModel, favoriteToggle: Boolean, onFav: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PortalNick(u.nickname, u.portalStatus, fontSize = 18)
        Muted("ID: ${u.id}", size = 11)
        Muted("Модель: ${if (u.model == "slim") "Alex (Slim)" else "Steve (Classic)"}  •  Регистрация: ${SimpleDateFormat("dd.MM.yyyy").format(Date(u.createdAt))}", size = 12)
        Muted("Статус: ${u.portalStatus.title}", size = 12)
    }
}

@Composable
private fun OtherProfile(u: PortalUser, vm: AppViewModel, onFavChanged: () -> Unit) {
    var fav by remember(u.id) { mutableStateOf(SocialClient.isFavorite(u.id)) }
    val skinFile by produceSkin(u, vm)
    val img = rememberSkinImage(skinFile)
    Panel(Modifier.fillMaxWidth()) {
        ProfileCard(u, vm, true, onFavChanged)
        GhostButton(if (fav) "★ Убрать из избранного" else "☆ В избранное", {
            fav = !fav; SocialClient.setFavorite(u, fav); onFavChanged()
        })
    }
    SkinViewerCard(img, u.model == "slim", Modifier.fillMaxWidth().weight(1f))
    if (u.skinHash == null) Muted("Пользователь ещё не публиковал скин.", size = 12)
}

@Composable
private fun produceSkin(u: PortalUser, vm: AppViewModel) = androidx.compose.runtime.produceState<java.nio.file.Path?>(null, u.id, u.skinHash) {
    value = try { SocialClient.skinFile(u) } catch (e: Throwable) { vm.showError(e); null }
}
