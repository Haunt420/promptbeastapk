package app.promptbeast

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A raised surface for one result / entry / group of controls. */
@Composable
fun PbCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun Dim(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun Gap(h: Int = 8) { Spacer(Modifier.height(h.dp)) }

@Composable
fun severityColor(severity: String): Color = when (severity) {
    "error" -> MaterialTheme.colorScheme.error
    "warn" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun severityGlyph(severity: String): String = when (severity) {
    "error" -> "●"
    "warn" -> "▲"
    else -> "○"
}

fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, "Share prompt").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun whenText(created: Double): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date((created * 1000).toLong()))
