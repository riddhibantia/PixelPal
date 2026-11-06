package com.pixelpal.app.presentation.screens.onboarding

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pixelpal.app.R
import com.pixelpal.app.animation.AnimationState
import com.pixelpal.app.domain.model.PetType
import com.pixelpal.app.overlay.OverlayService
import com.pixelpal.app.presentation.components.AppTextField
import com.pixelpal.app.presentation.components.GlassChip
import com.pixelpal.app.presentation.components.PetRenderer
import com.pixelpal.app.presentation.components.PrimaryButton
import com.pixelpal.app.presentation.components.SecondaryButton
import com.pixelpal.app.presentation.theme.DarkPalette
import com.pixelpal.app.presentation.theme.PixelPalGradients
import com.pixelpal.app.util.BridgeSetup
import com.pixelpal.app.util.Constants
import com.pixelpal.app.presentation.theme.Spacing
import com.pixelpal.app.util.PermissionHelper
import kotlinx.coroutines.launch

private val PAGE_COUNT = 5

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity

    val petName by viewModel.petName.collectAsState()
    val userName by viewModel.userName.collectAsState()
    val selectedPetType by viewModel.selectedPetType.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Subtle ambient background gradient that shifts across pages
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar: skip button (pages 0-3) + step label
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Step counter
                AnimatedContent(
                    targetState = pagerState.currentPage,
                    transitionSpec = {
                        fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                    },
                    label = "stepLabel"
                ) { page ->
                    Text(
                        text = stringResource(R.string.onboarding_step_counter, page + 1, PAGE_COUNT),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(visible = pagerState.currentPage < PAGE_COUNT - 1) {
                    TextButton(onClick = {
                        if (PermissionHelper.canDrawOverlays(context)) {
                            OverlayService.start(context)
                        }
                        viewModel.saveAndComplete(onOnboardingComplete)
                    }) {
                        Text(
                            stringResource(R.string.onboarding_action_skip),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Pages
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 0.dp),
                pageSpacing = 0.dp,
                userScrollEnabled = true
            ) { page ->
                when (page) {
                    0 -> PageWelcome()
                    1 -> PageChoosePet(
                        selectedType = selectedPetType,
                        onSelect = { viewModel.selectPetType(it) }
                    )
                    2 -> PageNamePet(
                        petName = petName,
                        userName = userName,
                        petType = selectedPetType,
                        onNameChange = { viewModel.updatePetName(it) },
                        onUserNameChange = { viewModel.updateUserName(it) }
                    )
                    3 -> PageEnableOverlay(
                        petName = petName,
                        petType = selectedPetType,
                        activity = activity
                    )
                    4 -> PageConnectAgent()
                }
            }

            // Bottom area: dot indicators + navigation buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal)
                    .padding(bottom = Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Pager dot indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = Spacing.md)
                ) {
                    repeat(PAGE_COUNT) { idx ->
                        val selected = pagerState.currentPage == idx
                        val width by animateDpAsState(
                            targetValue = if (selected) 24.dp else 6.dp,
                            animationSpec = spring(stiffness = Spring.StiffnessMedium),
                            label = "dotWidth"
                        )
                        val alpha by animateFloatAsState(
                            targetValue = if (selected) 1f else 0.35f,
                            animationSpec = tween(200),
                            label = "dotAlpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(
                                    role = Role.Tab,
                                    onClickLabel = stringResource(R.string.onboarding_step_action_label, idx + 1)
                                ) {
                                    scope.launch { pagerState.animateScrollToPage(idx) }
                                }
                                .semantics {
                                    contentDescription = "Step ${idx + 1} of $PAGE_COUNT"
                                    if (selected) stateDescription = "Current step"
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(width)
                                    .height(6.dp)
                                    .alpha(alpha)
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }

                // Navigation buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pagerState.currentPage > 0) {
                        SecondaryButton(
                            text = stringResource(R.string.onboarding_action_back),
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (pagerState.currentPage < PAGE_COUNT - 1) {
                        PrimaryButton(
                            text = stringResource(R.string.onboarding_action_continue),
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            },
                            modifier = if (pagerState.currentPage > 0)
                                Modifier.weight(1f)
                            else
                                Modifier.fillMaxWidth()
                        )
                    } else {
                        PrimaryButton(
                            text = stringResource(R.string.onboarding_action_get_started),
                            onClick = {
                                if (PermissionHelper.canDrawOverlays(context)) {
                                    OverlayService.start(context)
                                }
                                viewModel.saveAndComplete(onOnboardingComplete)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Page 0 — Welcome
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PageWelcome() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Pet animation — the star of the show
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            PetRenderer(
                petType = Constants.DEFAULT_PET_TYPE,
                animationState = AnimationState.WAVE,
                size = 160.dp
            )
        }

        Spacer(Modifier.height(Spacing.xl))

        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            ),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 24.sp
        )

        Spacer(Modifier.height(Spacing.xl))

        // Feature pill trio
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FeaturePill(
                icon = Icons.Filled.SmartToy,
                label = stringResource(R.string.onboarding_welcome_pill_agents)
            )
            FeaturePill(
                icon = Icons.Outlined.Pets,
                label = stringResource(R.string.onboarding_welcome_pill_growth)
            )
            FeaturePill(
                icon = Icons.Filled.Notifications,
                label = stringResource(R.string.onboarding_welcome_pill_alerts)
            )
        }
    }
}

@Composable
private fun FeaturePill(icon: ImageVector, label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Page 1 — Choose your companion species
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PageChoosePet(selectedType: String, onSelect: (String) -> Unit) {
    val effectiveType = selectedType.ifBlank { "cat" }
    val petInfo = PetType.fromId(effectiveType)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.onboarding_species_title),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            ),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = stringResource(R.string.onboarding_species_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(Spacing.lg))

        // Large animated preview in a soft circle
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            PetRenderer(
                petType = effectiveType,
                animationState = AnimationState.HAPPY,
                size = 164.dp
            )
        }

        Spacer(Modifier.height(Spacing.md))

        AnimatedContent(
            targetState = petInfo.displayName,
            transitionSpec = {
                (fadeIn(tween(180)) + slideInVertically { it / 3 }) togetherWith
                        (fadeOut(tween(120)) + slideOutVertically { -it / 3 })
            },
            label = "petName"
        ) { name ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = petInfo.description,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        // Species picker chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(PetType.entries) { entry ->
                GlassChip(
                    label = entry.displayName,
                    selected = effectiveType.equals(entry.id, ignoreCase = true),
                    onClick = { onSelect(entry.id) }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Page 2 — Name your companion
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PageNamePet(
    petName: String,
    userName: String,
    petType: String,
    onNameChange: (String) -> Unit,
    onUserNameChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            PetRenderer(
                petType = petType.ifBlank { "cat" },
                animationState = AnimationState.EXCITED,
                size = 148.dp
            )
        }

        Spacer(Modifier.height(Spacing.xl))

        Text(
            text = stringResource(R.string.onboarding_name_title),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            ),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = stringResource(R.string.onboarding_name_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(Spacing.lg))

        AppTextField(
            value = petName,
            onValueChange = { if (it.length <= 20) onNameChange(it) },
            label = stringResource(R.string.onboarding_name_label),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(Spacing.sm))

        // Live character counter
        Text(
            text = stringResource(R.string.onboarding_name_counter, petName.length),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (petName.length >= 18) 1f else 0.5f
            ),
            modifier = Modifier.align(Alignment.End)
        )

        Spacer(Modifier.height(Spacing.md))

        AppTextField(
            value = userName,
            onValueChange = { if (it.length <= 20) onUserNameChange(it) },
            label = stringResource(R.string.onboarding_user_name_label),
            placeholder = stringResource(R.string.onboarding_user_name_placeholder),
            modifier = Modifier.fillMaxWidth()
        )

        // Preview line
        AnimatedVisibility(visible = petName.isNotBlank()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(Spacing.lg))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.onboarding_name_ready, petName),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Page 3 — Enable screen overlay
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PageEnableOverlay(petName: String, petType: String, activity: Activity?) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(PermissionHelper.canDrawOverlays(context))
    }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = PermissionHelper.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val displayName = petName.ifBlank { stringResource(R.string.onboarding_default_companion_name) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Pet peeking out of a phone-shaped container (purely visual)
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            PetRenderer(
                petType = petType.ifBlank { "cat" },
                animationState = if (hasPermission) AnimationState.CELEBRATE else AnimationState.IDLE,
                size = 140.dp
            )
        }

        Spacer(Modifier.height(Spacing.xl))

        Text(
            text = if (hasPermission) stringResource(R.string.onboarding_overlay_all_set)
            else stringResource(R.string.onboarding_overlay_step_title, displayName),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            ),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = if (hasPermission)
                stringResource(R.string.onboarding_overlay_body_granted, displayName)
            else
                stringResource(R.string.onboarding_overlay_body_denied, displayName),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 24.sp
        )

        Spacer(Modifier.height(Spacing.xl))

        if (!hasPermission) {
            PrimaryButton(
                text = stringResource(R.string.onboarding_overlay_action_grant),
                modifier = Modifier.fillMaxWidth(),
                onClick = { activity?.let { PermissionHelper.requestOverlayPermission(it) } }
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.onboarding_overlay_skip_hint),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
        } else {
            // Success state
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.onboarding_overlay_granted_status, displayName),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Page 4 — Connect the agent bridge
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PageConnectAgent() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            PetRenderer(
                petType = Constants.DEFAULT_PET_TYPE,
                animationState = AnimationState.THINKING,
                size = 160.dp
            )
            // Terminal icon badge
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-12).dp, y = (-12).dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(Modifier.height(Spacing.xl))

        Text(
            text = stringResource(R.string.onboarding_bridge_title),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            ),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = stringResource(R.string.onboarding_bridge_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 24.sp
        )

        Spacer(Modifier.height(Spacing.lg))

        // Code block
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Traffic-light circles (decorative)
                        listOf(Color(0xFFFF5F57), Color(0xFFFFBD2E), Color(0xFF28C840)).forEach { c ->
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(c)
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(
                                android.content.Context.CLIPBOARD_SERVICE
                            ) as? android.content.ClipboardManager
                            clipboard?.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                    BridgeSetup.CLIPBOARD_LABEL,
                                    BridgeSetup.installCommand
                                )
                            )
                            copied = true
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (copied) stringResource(R.string.onboarding_bridge_copied)
                            else stringResource(R.string.onboarding_bridge_copy),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (copied)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = BridgeSetup.installCommand,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        lineHeight = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))

        Text(
            text = stringResource(R.string.onboarding_bridge_hint),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        )
    }
}

