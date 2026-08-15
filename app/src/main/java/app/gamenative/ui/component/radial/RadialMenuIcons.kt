package app.gamenative.ui.component.radial

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map as MapIcon
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsMartialArts
import androidx.compose.material.icons.filled.SportsMma
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * F (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.1/§1.2): mapa nome da
 * allowlist → Material icon (NUNCA asset — regra do spec). A allowlist em si vive
 * no core (`RadialMenuConfig.ICON_ALLOWLIST` — puro/testável); aqui só a
 * apresentação. Chave desconhecida/fora da allowlist = null (label só — nunca
 * crash; o parser já normaliza no load).
 *
 * Escolhas dos vetores (Material Icons, sem ícone literal de espada no conjunto):
 * sword → SportsMartialArts (lutador de artes marciais), potion → Science
 * (frasco), fight → SportsMma (luva de boxe), trade → SwapHoriz, craft → Build,
 * load → FileOpen, save → Save, bag → ShoppingBag, run → DirectionsRun.
 */
object RadialMenuIcons {

    /** Pares (iconKey, vetor) na ordem da allowlist — grade do editor e overlay. */
    val ALL: List<Pair<String, ImageVector>> = listOf(
        "sword" to Icons.Filled.SportsMartialArts,
        "potion" to Icons.Filled.Science,
        "map" to Icons.Filled.MapIcon,
        "bag" to Icons.Filled.ShoppingBag,
        "run" to Icons.Filled.DirectionsRun,
        "gear" to Icons.Filled.Settings,
        "heart" to Icons.Filled.Favorite,
        "star" to Icons.Filled.Star,
        "home" to Icons.Filled.Home,
        "save" to Icons.Filled.Save,
        "load" to Icons.Filled.FileOpen,
        "camera" to Icons.Filled.PhotoCamera,
        "chat" to Icons.Filled.Chat,
        "trade" to Icons.Filled.SwapHoriz,
        "craft" to Icons.Filled.Build,
        "fight" to Icons.Filled.SportsMma,
    )

    private val byKey: kotlin.collections.Map<String, ImageVector> = ALL.toMap()

    /** Ícone do setor; null = sem ícone (ausente ou fora da allowlist). */
    fun vectorFor(iconKey: String?): ImageVector? = iconKey?.let { byKey[it] }
}
