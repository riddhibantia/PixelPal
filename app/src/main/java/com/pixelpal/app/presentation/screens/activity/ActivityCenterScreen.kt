package com.pixelpal.app.presentation.screens.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.pixelpal.app.presentation.components.IconTile
import com.pixelpal.app.presentation.components.PixelPalAtmosphere
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pixelpal.app.domain.model.ActivityEvent
import com.pixelpal.app.animation.AnimationState
import com.pixelpal.app.R
import com.pixelpal.app.domain.model.ActivityType
import com.pixelpal.app.presentation.components.AppTopBar
import com.pixelpal.app.presentation.components.EmptyState
import com.pixelpal.app.presentation.components.GlassCard
import com.pixelpal.app.presentation.components.GlassChip
import com.pixelpal.app.presentation.components.GroupDivider
import com.pixelpal.app.presentation.theme.Radius
import com.pixelpal.app.presentation.theme.Spacing
import androidx.compose.runtime.remember
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun ActivityCenterScreen(
    navController: NavController,
    viewModel: ActivityCenterViewModel = hiltViewModel()
) {
    val events by viewModel.events.collectAsState()
    val companion by viewModel.companion.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val liveAgent by viewModel.liveAgent.collectAsState()

    PixelPalAtmosphere(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = stringResource(R.string.activity_title), onBack = { navController.popBackStack() })

            // WebSocket live typing banner — shows when agent is WORKING/ONLINE with a task
            if (liveAgent != null) {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs),
                    shape = RoundedCornerShape(Radius.medium)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(
                            text = liveAgent!!,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Companion filter chips (All + one per companion)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenHorizontal),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                GlassChip(
                    label = stringResource(R.string.activity_filter_all),
                    selected = selectedFilter == null,
                    onClick = { viewModel.filterBy(null) }
                )
                companion?.let { c ->
                    GlassChip(
                        label = c.name,
                        selected = selectedFilter == c.id,
                        onClick = { viewModel.filterBy(c.id) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            val groupedEvents = remember(events) {
                events.groupBy { dateGroupFor(it.createdAt) }
            }

            if (events.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.activity_empty_title),
                    message = stringResource(R.string.activity_empty_message),
                    petState = AnimationState.SLEEP,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.screenHorizontal)
                        .padding(bottom = Spacing.lg)
                ) {
                    DateGroup.entries.forEach { group ->
                        val items = groupedEvents[group]
                        if (!items.isNullOrEmpty()) {
                            Text(
                                text = group.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs, start = 4.dp)
                            )
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(Spacing.md)) {
                                    items.forEachIndexed { index, event ->
                                        if (index > 0) {
                                            GroupDivider()
                                        }
                                        ActivityEventRow(event = event)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityEventRow(event: ActivityEvent) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(
            icon = eventIcon(event.type),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            event.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = relativeTimestamp(event.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun eventIcon(type: ActivityType): ImageVector = when (type) {
    ActivityType.BOND_LEVEL_UP -> Icons.AutoMirrored.Filled.TrendingUp
    ActivityType.REMINDER_COMPLETED -> Icons.Default.Alarm
    ActivityType.TASK_ADDED, ActivityType.TASK_COMPLETED -> Icons.Default.TaskAlt
    ActivityType.AGENT_CHECKED, ActivityType.AGENT_STATUS_CHANGED -> Icons.Default.SmartToy
    ActivityType.COMPANION_ARCHIVED -> Icons.Default.Archive
    ActivityType.COMPANION_RESTORED -> Icons.Default.Restore
    else -> Icons.Default.Pets
}

private enum class DateGroup(val title: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    EARLIER("Earlier")
}

private fun dateGroupFor(timestamp: Long): DateGroup {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }

    val nowYear = now.get(Calendar.YEAR)
    val thenYear = then.get(Calendar.YEAR)
    val nowDay = now.get(Calendar.DAY_OF_YEAR)
    val thenDay = then.get(Calendar.DAY_OF_YEAR)

    if (nowYear == thenYear) {
        val daysDiff = nowDay - thenDay
        return when {
            daysDiff <= 0 -> DateGroup.TODAY
            daysDiff == 1 -> DateGroup.YESTERDAY
            daysDiff in 2..7 -> DateGroup.THIS_WEEK
            daysDiff in 8..30 -> DateGroup.THIS_MONTH
            else -> DateGroup.EARLIER
        }
    } else {
        val diffMillis = (now.timeInMillis - then.timeInMillis).coerceAtLeast(0)
        val diffDays = TimeUnit.MILLISECONDS.toDays(diffMillis)
        return when {
            diffDays <= 0L -> DateGroup.TODAY
            diffDays == 1L -> DateGroup.YESTERDAY
            diffDays in 2L..7L -> DateGroup.THIS_WEEK
            diffDays in 8L..30L -> DateGroup.THIS_MONTH
            else -> DateGroup.EARLIER
        }
    }
}

private fun relativeTimestamp(time: Long): String {
    val diff = (System.currentTimeMillis() - time).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days == 1L -> "Yesterday"
        days < 7L -> "${days}d ago"
        else -> {
            val cal = Calendar.getInstance().apply { timeInMillis = time }
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(cal.time)
        }
    }
}