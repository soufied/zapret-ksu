package com.so.zapretksu.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class Host(val label: String, val background: Color, val content: Color) {
    GitHub("GitHub", Color(0xFF24292F), Color.White),
    Forgejo("Forgejo", Color(0xFFFB923C), Color(0xFF1B1B1F)),
    Web("Website", Color(0xFF5C6BC0), Color.White)
}

private data class Credit(val repo: String, val url: String, val note: String)

private val MAIN_REPO = Credit(
    repo = "soufied/zapret-ksu",
    url = "https://github.com/soufied/zapret-ksu",
    note = "Main repository of this project"
)

private val CREDITS: List<Credit> = listOf(
    Credit(
        "valnesfjord/tg-ws-proxy-rs",
        "https://github.com/valnesfjord/tg-ws-proxy-rs",
        "tg-ws-proxy: the engine behind the TG WS Proxy tool"
    ),
    Credit("bol-van/zapret", "https://github.com/bol-van/zapret", "nfqws, the zapret 1 engine"),
    Credit("bol-van/zapret2", "https://github.com/bol-van/zapret2", "nfqws2, the zapret 2 engine"),
    Credit("Flowseal/zapret-discord-youtube", "https://github.com/Flowseal/zapret-discord-youtube", "Zapret 1 strategies"),
    Credit(
        "zapretdiscordyoutube/zapret2-youtube-discord",
        "https://git.zapret.moe/zapretdiscordyoutube/zapret2-youtube-discord",
        "Zapret 2 presets, Lua scripts and lists"
    ),
    Credit(
        "zapretdiscordyoutube/magisk-zapret2",
        "https://git.zapret.moe/zapretdiscordyoutube/magisk-zapret2",
        "The reference this module is based on"
    ),
    Credit("DNSCrypt/dnscrypt-proxy", "https://github.com/DNSCrypt/dnscrypt-proxy", "DNS proxy"),
    Credit("topjohnwu/libsu", "https://github.com/topjohnwu/libsu", "Root shell access in the app"),
    Credit("Abdullajon1881/LiquidGlass", "https://github.com/Abdullajon1881/LiquidGlass", "Liquid Glass rendering"),
    Credit("github.akams.cn", "https://github.akams.cn/", "GitHub proxy used when GitHub is unreachable")
)

private fun brandIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
        .build()

private val GitHubMark: ImageVector by lazy {
    brandIcon(
        "GitHubMark",
        """M12 1C5.923 1 1 5.923 1 12c0 4.867 3.149 8.979 7.521 10.436.55.096.756-.233.756-.522 0-.262-.013-1.128-.013-2.049-2.764.509-3.479-.674-3.699-1.292-.124-.317-.66-1.293-1.127-1.554-.385-.207-.936-.715-.014-.729.866-.014 1.485.797 1.691 1.128.99 1.663 2.571 1.196 3.204.907.096-.715.385-1.196.701-1.471-2.448-.275-5.005-1.224-5.005-5.432 0-1.196.426-2.186 1.128-2.956-.111-.275-.496-1.402.11-2.915 0 0 .921-.288 3.024 1.128a10.2 10.2 0 0 1 2.75-.371c.936 0 1.871.123 2.75.371 2.104-1.43 3.025-1.128 3.025-1.128.605 1.513.221 2.64.111 2.915.701.77 1.127 1.747 1.127 2.956 0 4.222-2.571 5.157-5.019 5.432.399.344.743 1.004.743 2.035 0 1.471-.014 2.654-.014 3.025 0 .289.206.632.756.522C19.851 20.979 23 16.854 23 12c0-6.077-4.922-11-11-11"""
    )
}

private val ForgejoMark: ImageVector by lazy {
    brandIcon(
        "ForgejoMark",
        """M16.777 0a2.9 2.9 0 1 1-2.529 4.322H12.91a4.266 4.266 0 0 0-4.265 4.195v2.118a7.08 7.08 0 0 1 4.147-1.42l.118-.002h1.338a2.9 2.9 0 0 1 5.43 1.422 2.9 2.9 0 0 1-5.43 1.422H12.91a4.266 4.266 0 0 0-4.265 4.195v2.319A2.9 2.9 0 0 1 7.222 24 2.9 2.9 0 0 1 5.8 18.57V8.589a7.11 7.11 0 0 1 6.991-7.108l.118-.001h1.338A2.9 2.9 0 0 1 16.778 0M7.223 19.905a1.194 1.194 0 1 0 0 2.389 1.194 1.194 0 0 0 0-2.389m9.554-10.464a1.194 1.194 0 1 0 0 2.389 1.194 1.194 0 0 0 0-2.39m0-7.735a1.194 1.194 0 1 0 0 2.389 1.194 1.194 0 0 0 0-2.389"""
    )
}

private fun Host.icon(): ImageVector = when (this) {
    Host.GitHub -> GitHubMark
    Host.Forgejo -> ForgejoMark
    Host.Web -> Icons.Filled.Language
}

private fun hostOf(url: String): Host = when (java.net.URI(url).host) {
    "github.com" -> Host.GitHub
    "git.zapret.moe" -> Host.Forgejo
    else -> Host.Web
}

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
    }

    Page {
        AboutHeader(version)

        SectionLabel("Main repository")
        AppCard(Modifier.fillMaxWidth()) {
            CreditRow(MAIN_REPO, featured = true)
        }

        SectionLabel("Credits")
        AppCard(Modifier.fillMaxWidth()) {
            CREDITS.forEachIndexed { index, credit ->
                if (index > 0) HorizontalDivider()
                CreditRow(credit, featured = false)
            }
        }

        SectionLabel("License")
        AppCard(Modifier.fillMaxWidth()) {
            Text("GPL-3.0-only", style = MaterialTheme.typography.titleSmall)
            Text(
                "The full text is in the LICENSE file of the main repository.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AboutHeader(version: String) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
            Text("zapret KSU", style = MaterialTheme.typography.headlineSmall)
            Text(
                "DPI bypass for KernelSU",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val heartScale by if (LocalAppearance.current.optimized) {
                remember { mutableStateOf(1f) }
            } else {
                rememberInfiniteTransition(label = "heart").animateFloat(
                    initialValue = 1f,
                    targetValue = 1.25f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "heartScale"
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "made by soufi with",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "love",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer {
                            scaleX = heartScale
                            scaleY = heartScale
                        }
                )
            }
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    "Freedom is everything",
                    style = MaterialTheme.typography.titleSmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoTile("Version", version.ifBlank { "unknown" }, Modifier.weight(1f))
                InfoTile("Author", "soufi", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(listRowColor(), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

@Composable
private fun CreditRow(credit: Credit, featured: Boolean) {
    val context = LocalContext.current
    val host = hostOf(credit.url)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .tvFocus(RoundedCornerShape(16.dp))
            .clickable {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(credit.url)))
                }
            }
            .padding(vertical = if (featured) 6.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(if (featured) 44.dp else 36.dp)
                .background(host.background, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(host.icon(), contentDescription = null, tint = host.content, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                credit.repo,
                style = if (featured) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                credit.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = "Open ${credit.repo}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}
