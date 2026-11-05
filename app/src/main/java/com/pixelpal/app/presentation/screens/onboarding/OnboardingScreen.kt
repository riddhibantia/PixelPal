package com.pixelpal.app.presentation.screens.onboarding

import android.app.Activity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pixelpal.app.animation.AnimationState
import com.pixelpal.app.overlay.OverlayService
import com.pixelpal.app.presentation.components.AppTextField
import com.pixelpal.app.presentation.components.GlassChip
import com.pixelpal.app.presentation.components.PetRenderer
import com.pixelpal.app.presentation.components.PrimaryButton
import com.pixelpal.app.presentation.components.SecondaryButton
import com.pixelpal.app.presentation.theme.Spacing
import com.pixelpal.app.util.PermissionHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val pagerState = rememberPagerState(pageCount = { 5 })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity

    val petName by viewModel.petName.collectAsState()
    val selectedPetType by viewModel.selectedPetType.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            when (page) {
                0 -> PageWelcome()
                1 -> PageChoosePet(
                    selectedType = selectedPetType,
                    onSelect = { viewModel.selectPetType(it) }
                )
                2 -> PageNamePet(petName, onNameChange = { viewModel.updatePetName(it) })
                3 -> PageEnableOverlay(petName = petName, activity = activity)
                4 -> PageConnectAgent()
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (pagerState.currentPage > 0) {
                SecondaryButton(
                    text = "Back",
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            if (pagerState.currentPage < 4) {
                PrimaryButton(
                    text = "Next",
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    },
                    modifier = if (pagerState.currentPage > 0) Modifier.weight(1f) else Modifier.fillMaxWidth()
                )
            } else {
                PrimaryButton(
                    text = "Let's Go!",
                    onClick = {
                        if (PermissionHelper.canDrawOverlays(context)) {
                            OverlayService.start(context)
                        }
                        viewModel.saveAndComplete(onOnboardingComplete)
                    }
                )
            }
        }
    }
}

@Composable
private fun PageWelcome() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PetRenderer(petType = "cat", animationState = AnimationState.IDLE, size = 180.dp)
        Spacer(modifier = Modifier.height(Spacing.xl))
        Text(
            text = "Welcome to PixelPal",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "Your tiny companion is waiting to meet you.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun PageChoosePet(selectedType: String, onSelect: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Choose Your Companion",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(Spacing.lg))
        PetRenderer(
            petType = selectedType.ifBlank { "cat" },
            animationState = AnimationState.HAPPY,
            size = 180.dp
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = selectedType.replaceFirstChar { it.uppercase() }.ifBlank { "Cat" },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            items(com.pixelpal.app.domain.model.PetType.entries.size) { index ->
                val entry = com.pixelpal.app.domain.model.PetType.entries[index]
                val selected = selectedType.equals(entry.id, ignoreCase = true)
                GlassChip(
                    label = entry.displayName,
                    selected = selected,
                    onClick = { onSelect(entry.id) }
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = com.pixelpal.app.domain.model.PetType.fromId(selectedType.ifBlank { "cat" }).description,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PageNamePet(petName: String, onNameChange: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PetRenderer(petType = "cat", animationState = AnimationState.EXCITED, size = 150.dp)
        Spacer(modifier = Modifier.height(Spacing.lg))
        Text(
            text = "Name Your Companion",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        AppTextField(
            value = petName,
            onValueChange = { if (it.length <= 20) onNameChange(it) },
            label = "Companion Name",
            modifier = Modifier.fillMaxWidth(0.85f)
        )
    }
}

/**
 * Fifth page: the app's core feature is invisible until a bridge is running on
 * the computer, so the install command is shown here instead of being buried in
 * the README. Skippable — pairing can happen any time from the Agent tab.
 */
@Composable
private fun PageConnectAgent() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Watch Your Agent Work",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "PixelPal follows Claude Code, Codex, OMP, opencode and Hermes " +
                "through one small bridge on your computer.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.lg))

        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Text(
                text = CONNECT_COMMANDS,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.md)
            )
        }

        Spacer(modifier = Modifier.height(Spacing.md))
        SecondaryButton(
            text = if (copied) "Copied" else "Copy commands",
            modifier = Modifier.fillMaxWidth(0.9f),
            onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("PixelPal bridge", CONNECT_COMMANDS)
                )
                copied = true
            }
        )

        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "Then paste the address it prints into the Agent tab. " +
                "You can skip this and pair later.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Resolves the newest published release, so this keeps working as versions
// ship and never points at a branch that may not carry the script yet.
private const val CONNECT_COMMANDS =
    "curl -fsSL https://github.com/riddhibantia/PixelPal/releases/latest/download/install.sh | bash\n" +
        "pixelpal-bridge setup"

@Composable
private fun PageEnableOverlay(petName: String, activity: Activity?) {
    val context = LocalContext.current
    val hasPermission = PermissionHelper.canDrawOverlays(context)

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Let $petName Join You!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "$petName lives on your phone screen as a tiny pixel pet overlay.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.lg))
        PrimaryButton(
            text = if (hasPermission) "Permission Granted" else "Enable Screen Overlay",
            modifier = Modifier.fillMaxWidth(0.85f),
            onClick = {
                activity?.let { PermissionHelper.requestOverlayPermission(it) }
            }
        )
    }
}