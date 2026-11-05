package com.pixelpal.app.presentation.screens.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pixelpal.app.R
import com.pixelpal.app.presentation.components.AppTextField
import com.pixelpal.app.presentation.components.AppTopBar
import com.pixelpal.app.presentation.components.GlassCard
import com.pixelpal.app.presentation.components.PixelPalAtmosphere
import com.pixelpal.app.presentation.components.PrimaryButton
import com.pixelpal.app.presentation.components.SectionHeader
import com.pixelpal.app.presentation.theme.Radius
import com.pixelpal.app.presentation.theme.Spacing

/** A subtask draft on the New Task screen, id-keyed so removal keeps edits stable. */
/**
 * Dedicated task editor: title, optional description and any number of
 * subtask drafts. Nothing is written until "Create Task" is tapped.
 */
@Composable
fun NewTaskScreen(
    navController: NavController,
    viewModel: TasksViewModel = hiltViewModel()
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var dueAt by rememberSaveable { mutableStateOf<Long?>(null) }
    // Draft subtasks survive rotation / process death: a half-written task with
    // five subtasks used to vanish when the phone turned.
    val drafts = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })
    ) { mutableStateListOf<String>() }

    fun addDraft() {
        drafts.add("")
    }

    Scaffold { padding ->
        PixelPalAtmosphere(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                AppTopBar(
                    title = stringResource(R.string.tasks_new_task_title),
                    onBack = { navController.popBackStack() }
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.screenHorizontal)
                        .padding(top = Spacing.sm, bottom = Spacing.md)
                ) {
                AppTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = stringResource(R.string.tasks_field_task_title),
                    placeholder = stringResource(R.string.tasks_field_task_title_placeholder)
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                AppTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = stringResource(R.string.tasks_field_description),
                    placeholder = stringResource(R.string.tasks_field_description_placeholder),
                    singleLine = false,
                    modifier = Modifier.height(120.dp)
                )

                Spacer(modifier = Modifier.height(Spacing.lg))

                SectionHeader(title = stringResource(R.string.tasks_due_date_title))
                DueDateSection(dueAt = dueAt, onDueAtChange = { dueAt = it })

                Spacer(modifier = Modifier.height(Spacing.lg))

                SectionHeader(title = stringResource(R.string.tasks_subtasks_title))

                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
                        if (drafts.isEmpty()) {
                            Text(
                                text = stringResource(R.string.tasks_new_subtasks_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                            )
                        }

                        drafts.forEachIndexed { index, draft ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = false, onCheckedChange = null)
                                AppTextField(
                                    value = draft,
                                    onValueChange = { drafts[index] = it },
                                    label = stringResource(R.string.tasks_subtask_number_label, index + 1),
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { drafts.removeAt(index) }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.tasks_subtask_content_description_remove),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                AddSubtaskButton(onClick = { addDraft() })
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md)
                    .navigationBarsPadding()
            ) {
                PrimaryButton(
                    text = stringResource(R.string.tasks_action_create),
                    enabled = title.isNotBlank(),
                    onClick = {
                        viewModel.createTask(
                            title = title,
                            description = description.takeIf { it.isNotBlank() },
                            subtaskTitles = drafts,
                            dueAt = dueAt
                        )
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
}

/** The "+ Add subtask" affordance — quiet, bordered, gold text. */
@Composable
private fun AddSubtaskButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.medium),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.tasks_action_add_subtask),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
