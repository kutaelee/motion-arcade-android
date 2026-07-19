package com.motionarcade.app.fishing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.motionarcade.app.R
import com.motionarcade.app.SharedScoreComboHud
import kotlinx.coroutines.delay

/** Render-only state. Game rules remain owned by :games and motion confirmation by :game-core. */
@Immutable
internal data class FishingHudModel(
    val phaseLabel: String,
    val instruction: String,
    val scoreLabel: String,
    val progressLabel: String,
    val progressDescription: String,
    val progress: Float,
    val tensionLabel: String,
    val tensionDescription: String,
    val tension: Float,
    val trackingStatusLabel: String,
    val actionLabel: String,
    val actionEnabled: Boolean,
    val pauseLabel: String,
    val pauseEnabled: Boolean,
    val artFrame: Int = 0,
    val showCatchMeasurement: Boolean = false,
) {
    init {
        require(progress.isFinite() && progress in 0f..1f)
        require(tension.isFinite() && tension in 0f..1f)
        require(artFrame in 0 until FISHING_ART_FRAME_COUNT)
    }
}

@Immutable
internal data class FishingArtPlaybackFrame(
    val catchSheet: Boolean,
    val frame: Int,
) {
    init {
        require(frame in 0 until FISHING_ART_FRAME_COUNT)
    }
}

internal fun fishingArtPlayback(
    showCatchMeasurement: Boolean,
    requestedGameplayFrame: Int,
): List<FishingArtPlaybackFrame> {
    require(requestedGameplayFrame in 0 until FISHING_ART_FRAME_COUNT)
    if (!showCatchMeasurement) {
        return listOf(FishingArtPlaybackFrame(catchSheet = false, frame = requestedGameplayFrame))
    }
    return buildList {
        add(FishingArtPlaybackFrame(catchSheet = false, frame = FISHING_ART_FRAME_COUNT - 1))
        repeat(FISHING_ART_FRAME_COUNT) { frame ->
            add(FishingArtPlaybackFrame(catchSheet = true, frame = frame))
        }
    }
}

/** Responsive fishing overlay using code-rendered labels over the registered ImageGen art. */
@Composable
internal fun FishingHud(
    model: FishingHudModel,
    onAction: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        FishingDeepWater.copy(alpha = 0.22f),
                        Color.Transparent,
                        FishingDeepWater.copy(alpha = 0.34f),
                    ),
                ),
            )
            .safeDrawingPadding()
            .padding(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FishingEdgeStatus(model)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                FishingPlayfield(
                    model = model,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .border(2.dp, FishingFoam.copy(alpha = 0.44f), RoundedCornerShape(20.dp))
                        .semantics {
                            contentDescription = "16:9 낚시 플레이필드. 어깨, 팔꿈치, 손목, 엉덩이만 화면에 보이면 플레이할 수 있습니다."
                        },
                )
                Text(
                    text = model.instruction,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .background(FishingOutline.copy(alpha = 0.78f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    color = FishingFoam,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
            FishingEdgeControls(model, onAction, onPause)
        }
    }
}

@Composable
internal fun FishingPlayfield(
    model: FishingHudModel,
    modifier: Modifier,
) {
    val harbor = ImageBitmap.imageResource(R.drawable.fishing_harbor_day_v1)
    val rod = ImageBitmap.imageResource(R.drawable.fishing_rod_v1)
    val fish = ImageBitmap.imageResource(R.drawable.fishing_fish_family_v1)
    val waterFx = ImageBitmap.imageResource(R.drawable.fishing_water_fx_v1)
    Canvas(modifier) {
        val width = size.width.toInt()
        val height = size.height.toInt()
        drawImage(
            image = harbor,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(harbor.width, harbor.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, height),
        )
        val rodShift = ((model.artFrame - 1.5f) * width * 0.014f).toInt()
        drawImage(
            image = rod,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(rod.width, rod.height),
            dstOffset = IntOffset((-width * 0.04f).toInt() + rodShift, (height * 0.10f).toInt()),
            dstSize = IntSize((width * 0.42f).toInt(), (height * 0.82f).toInt()),
        )
        val fishCell = model.artFrame.coerceIn(0, 3)
        drawImage(
            image = fish,
            srcOffset = IntOffset(fishCell * 384, 0),
            srcSize = IntSize(384, 512),
            dstOffset = IntOffset((width * 0.58f).toInt(), (height * 0.56f).toInt()),
            dstSize = IntSize((width * 0.23f).toInt(), (height * 0.32f).toInt()),
        )
        val fxCell = if (model.showCatchMeasurement) 5 else model.artFrame.coerceIn(0, 2)
        drawImage(
            image = waterFx,
            srcOffset = IntOffset((fxCell % 3) * 512, (fxCell / 3) * 512),
            srcSize = IntSize(512, 512),
            dstOffset = IntOffset((width * 0.49f).toInt(), (height * 0.47f).toInt()),
            dstSize = IntSize((width * 0.34f).toInt(), (height * 0.48f).toInt()),
        )
    }
}

@Composable
private fun FishingEdgeStatus(model: FishingHudModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FishingOutline.copy(alpha = 0.84f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(model.phaseLabel, color = FishingFoam, fontWeight = FontWeight.Bold)
        Text(model.scoreLabel, color = FishingSand, fontWeight = FontWeight.Black)
        Text(model.trackingStatusLabel, color = FishingSeaTeal, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun FishingEdgeControls(
    model: FishingHudModel,
    onAction: () -> Unit,
    onPause: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FishingOutline.copy(alpha = 0.86f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FishingCompactMeter(model.progressLabel, model.progressDescription, model.progress, FishingSeaTeal)
            FishingCompactMeter(model.tensionLabel, model.tensionDescription, model.tension, FishingCoral)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPause,
                    enabled = model.pauseEnabled,
                    modifier = Modifier.weight(0.38f),
                ) { Text(model.pauseLabel, color = FishingFoam) }
                Button(
                    onClick = onAction,
                    enabled = model.actionEnabled,
                    modifier = Modifier.weight(0.62f),
                    colors = ButtonDefaults.buttonColors(containerColor = FishingCoral),
                ) { Text(model.actionLabel, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun FishingCompactMeter(
    label: String,
    description: String,
    progress: Float,
    color: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(0.28f), color = FishingFoam, style = MaterialTheme.typography.labelMedium)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .weight(0.72f)
                .height(8.dp)
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                },
            color = color,
            trackColor = FishingSand.copy(alpha = 0.22f),
        )
    }
}

private const val FISHING_ART_FRAME_COUNT = 4
private const val FISHING_ART_FRAME_DURATION_MS = 140L
private const val FISHING_PORTRAIT_FRAME_WIDTH_PX = 540
private const val FISHING_PORTRAIT_FRAME_HEIGHT_PX = 960

private fun portraitAtlasOffset(frame: Int): IntOffset = IntOffset(
    x = (frame % 2) * FISHING_PORTRAIT_FRAME_WIDTH_PX,
    y = (frame / 2) * FISHING_PORTRAIT_FRAME_HEIGHT_PX,
)

@Composable
private fun FishingStatusCard(
    model: FishingHudModel,
    modifier: Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = FishingFoam.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            SharedScoreComboHud(
                primaryText = model.scoreLabel,
                secondaryText = model.phaseLabel,
                frame = model.artFrame,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = model.phaseLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = FishingOutline,
                )
                Text(
                    text = model.scoreLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = FishingRareViolet,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = model.instruction,
                style = MaterialTheme.typography.bodyLarge,
                color = FishingOutline,
            )
            Spacer(modifier = Modifier.height(16.dp))
            FishingMeter(
                label = model.progressLabel,
                description = model.progressDescription,
                progress = model.progress,
                color = FishingSeaTeal,
            )
            Spacer(modifier = Modifier.height(12.dp))
            FishingMeter(
                label = model.tensionLabel,
                description = model.tensionDescription,
                progress = model.tension,
                color = FishingCoral,
            )
        }
    }
}

@Composable
private fun FishingMeter(
    label: String,
    description: String,
    progress: Float,
    color: Color,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = FishingOutline,
    )
    Spacer(modifier = Modifier.height(6.dp))
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            },
        color = color,
        trackColor = FishingSand.copy(alpha = 0.38f),
    )
}

@Composable
private fun FishingActionCard(
    model: FishingHudModel,
    onAction: () -> Unit,
    onPause: () -> Unit,
    compactHeight: Boolean,
    modifier: Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = FishingOutline.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = if (compactHeight) {
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            } else {
                Modifier.padding(18.dp)
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (compactHeight) {
                FishingPrimaryAction(model, onAction)
                Spacer(modifier = Modifier.height(10.dp))
                FishingTrackingStatus(model)
                Spacer(modifier = Modifier.height(12.dp))
                FishingPauseAction(model, onPause)
            } else {
                FishingTrackingStatus(model)
                Spacer(modifier = Modifier.height(12.dp))
                FishingPauseAction(model, onPause)
                Spacer(modifier = Modifier.height(10.dp))
                FishingPrimaryAction(model, onAction)
            }
        }
    }
}

@Composable
private fun FishingTrackingStatus(model: FishingHudModel) {
    Text(
        text = model.trackingStatusLabel,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelLarge,
        color = FishingFoam,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun FishingPauseAction(model: FishingHudModel, onPause: () -> Unit) {
    OutlinedButton(
        onClick = onPause,
        enabled = model.pauseEnabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = model.pauseLabel, color = FishingFoam)
    }
}

@Composable
private fun FishingPrimaryAction(model: FishingHudModel, onAction: () -> Unit) {
    Button(
        onClick = onAction,
        enabled = model.actionEnabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = FishingCoral,
            contentColor = FishingOutline,
        ),
    ) {
        Text(text = model.actionLabel, fontWeight = FontWeight.Bold)
    }
}

private val FishingOutline = Color(0xFF14364A)
private val FishingSeaTeal = Color(0xFF2BA9A1)
private val FishingDeepWater = Color(0xFF176B87)
private val FishingFoam = Color(0xFFEAF9F4)
private val FishingCoral = Color(0xFFF47B61)
private val FishingSand = Color(0xFFE9C77C)
private val FishingRareViolet = Color(0xFF7656C9)
