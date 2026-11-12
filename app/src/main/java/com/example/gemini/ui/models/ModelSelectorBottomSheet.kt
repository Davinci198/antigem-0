package com.example.gemini.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.example.gemini.theme.isAppInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ModelFamily
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.theme.*
import kotlin.math.roundToInt

enum class CategoryType {
    GEMINI,
    CLAUDE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorBottomSheet(
    selectedModelId: String,
    availableModels: List<AiModel>,
    quotas: List<ModelQuota>,
    quotaSummary: com.example.gemini.domain.model.QuotaSummaryResponse? = null,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Group models into two groups: Claude and Gemini (with GPT-OSS and others under Gemini)
    val claudeModels = availableModels.filter {
        (it.family == ModelFamily.CLAUDE || it.id.contains("claude", ignoreCase = true) || it.displayName.contains("claude", ignoreCase = true)) &&
                !it.id.contains("gpt", ignoreCase = true) && !it.displayName.contains("gpt", ignoreCase = true)
    }
    val geminiModels = availableModels.filter { it !in claudeModels }

    val activeModel = availableModels.find { it.id == selectedModelId }

    // Navigation state inside the sheet: null = category list, CategoryType = subcategory sheet
    var activeCategory by remember { mutableStateOf<CategoryType?>(null) }

    val sheetState = rememberModalBottomSheetState(
        // the model list is the point of this sheet: open it full height, not at 78%
        skipPartiallyExpanded = true
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        // Intercept back press when inside a subcategory inside the sheet window
        BackHandler(enabled = activeCategory != null) {
            activeCategory = null
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 18.dp)
        ) {
            AnimatedContent(
                targetState = activeCategory,
                transitionSpec = {
                    if (targetState != null) {
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> width } + fadeOut()
                    }
                },
                modifier = Modifier.fillMaxSize(),
                label = "category_transition"
            ) { currentCategory ->
                if (currentCategory == null) {
                    // Main Level: Active Model + Category Selection Tiles
                    MainCategoryListView(
                        activeModel = activeModel,
                        availableModels = availableModels,
                        claudeModelsCount = claudeModels.size,
                        geminiModelsCount = geminiModels.size,
                        availableModelsCount = availableModels.size,
                        quotas = quotas,
                        quotaSummary = quotaSummary,
                        isRefreshing = isRefreshing,
                        onRefresh = onRefresh,
                        onSelectCategory = { category -> activeCategory = category },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Sub-Sheet: Models inside the chosen category
                    val (categoryTitle, brandColor, categoryModels) = when (currentCategory) {
                        CategoryType.GEMINI -> Triple("Google Gemini", GeminiBlue, geminiModels)
                        CategoryType.CLAUDE -> Triple("Anthropic Claude", ClaudeTerracotta, claudeModels)
                    }

                    CategoryModelsSubView(
                        title = categoryTitle,
                        brandColor = brandColor,
                        models = categoryModels,
                        selectedModelId = selectedModelId,
                        onBack = {
                            activeCategory = null
                        },
                        onSelectModel = { modelId ->
                            onSelectModel(modelId)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * Main Level Sheet View
 */
/**
 * Helpers to format clean, modern quota lines like "5h · 15% · 2h 4m" and "Weekly · 85% · 3d 21h".
 */
fun formatCleanCountdown(isoString: String?, rawCountdown: String? = null): String {
    if (!isoString.isNullOrBlank()) {
        try {
            val clean = isoString.substringBefore('.').substringBefore('Z')
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val target = sdf.parse(clean)?.time
            if (target != null) {
                val diffMs = target - System.currentTimeMillis()
                if (diffMs <= 0) return "Ready"
                val totalMins = diffMs / (1000 * 60)
                val days = totalMins / (60 * 24)
                val hours = (totalMins % (60 * 24)) / 60
                val mins = totalMins % 60
                return when {
                    days > 0 && hours > 0 -> "${days}d ${hours}h"
                    days > 0 -> "${days}d"
                    hours > 0 && mins > 0 -> "${hours}h ${mins}m"
                    hours > 0 -> "${hours}h"
                    else -> "${mins}m"
                }
            }
        } catch (_: Exception) {}
    }
    if (!rawCountdown.isNullOrBlank()) {
        return rawCountdown.removePrefix("Resets in ").removePrefix("Reset in ").removePrefix("• ").trim()
    }
    return ""
}

fun buildQuotaSummaryLine(
    windowLabel: String,
    fraction: Float?,
    resetTime: String?,
    rawCountdown: String?
): String? {
    if (fraction == null) return null
    val pct = (fraction * 100f).roundToInt().coerceIn(0, 100)
    val time = formatCleanCountdown(resetTime, rawCountdown)
    return if (time.isNotBlank()) {
        "$windowLabel · $pct% · $time"
    } else {
        "$windowLabel · $pct%"
    }
}

@Composable
fun QuotaBadgeChip(
    text: String,
    fraction: Float?,
    modifier: Modifier = Modifier
) {
    val pct = fraction?.let { (it * 100f).roundToInt().coerceIn(0, 100) } ?: 100
    val dotColor = when {
        pct > 50 -> QuotaGreen
        pct > 20 -> QuotaAmber
        else -> QuotaRed
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = text,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                letterSpacing = 0.1.sp
            )
        }
    }
}

/**
 * Main Level Sheet View
 */
@Composable
private fun MainCategoryListView(
    activeModel: AiModel?,
    availableModels: List<AiModel>,
    claudeModelsCount: Int,
    geminiModelsCount: Int,
    availableModelsCount: Int,
    quotas: List<ModelQuota>,
    quotaSummary: com.example.gemini.domain.model.QuotaSummaryResponse? = null,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSelectCategory: (CategoryType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp)
    ) {
        // Agent header (same as the Claude Code picker)
        com.example.gemini.ui.agents.AgentSheetHeader(
            agent = com.example.gemini.domain.model.AgentKind.AGY,
            title = "Select model",
            detail = if (availableModelsCount > 0) "$availableModelsCount models" else "no models loaded",
            refreshing = isRefreshing,
            onRefresh = onRefresh
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (availableModelsCount == 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No Models Loaded",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Connect your Google account in Settings or tap refresh to load your active models.",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // Category Navigation Tiles: Gemini FIRST, Claude SECOND
            val isClaudeActive = activeModel != null && (
                activeModel.family == ModelFamily.CLAUDE ||
                activeModel.id.contains("claude", ignoreCase = true) ||
                activeModel.displayName.contains("claude", ignoreCase = true)
            ) && !activeModel.id.contains("gpt", ignoreCase = true) && !activeModel.displayName.contains("gpt", ignoreCase = true)
            val isGeminiActive = activeModel != null && !isClaudeActive

            val cleanActiveModelName = activeModel?.displayName
                ?.replace(Regex("\\s*\\(?Thinking\\)?", RegexOption.IGNORE_CASE), "")
                ?.trim()

            val geminiGroup = quotaSummary?.groups?.find {
                it.groupId == "gemini" || it.groupName.contains("gemini", ignoreCase = true)
            }
            val claudeGroup = quotaSummary?.groups?.find {
                it.groupId == "claude_gpt" || it.groupName.contains("claude", ignoreCase = true) || it.groupName.contains("gpt", ignoreCase = true)
            }

            // 1. Google Gemini (First!)
            if (geminiModelsCount > 0) {
                val fiveHour = geminiGroup?.fiveHour
                val weekly = geminiGroup?.weekly
                val fiveHourFraction = fiveHour?.remainingFraction ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.GEMINI } }?.remainingFraction
                val weeklyFraction = weekly?.remainingFraction ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.GEMINI } }?.weeklyRemainingFraction

                val line1 = buildQuotaSummaryLine(
                    "5h",
                    fiveHourFraction,
                    fiveHour?.resetTime,
                    fiveHour?.countdown ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.GEMINI } }?.resetCountdown
                )
                val line2 = buildQuotaSummaryLine(
                    "7d",
                    weeklyFraction,
                    weekly?.resetTime,
                    weekly?.countdown ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.GEMINI } }?.weeklyResetCountdown
                )

                CategoryNavigationTile(
                    title = "Google Gemini",
                    activeModelName = if (isGeminiActive) cleanActiveModelName else null,
                    line1 = line1,
                    fraction1 = fiveHourFraction,
                    line2 = line2,
                    fraction2 = weeklyFraction,
                    count = geminiModelsCount,
                    brandColor = GeminiBlue,
                    onClick = { onSelectCategory(CategoryType.GEMINI) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 2. Anthropic Claude (Second, no GPT in title!)
            if (claudeModelsCount > 0) {
                val fiveHour = claudeGroup?.fiveHour
                val weekly = claudeGroup?.weekly
                val fiveHourFraction = fiveHour?.remainingFraction ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.CLAUDE } }?.remainingFraction
                val weeklyFraction = weekly?.remainingFraction ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.CLAUDE } }?.weeklyRemainingFraction

                val line1 = buildQuotaSummaryLine(
                    "5h",
                    fiveHourFraction,
                    fiveHour?.resetTime,
                    fiveHour?.countdown ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.CLAUDE } }?.resetCountdown
                )
                val line2 = buildQuotaSummaryLine(
                    "7d",
                    weeklyFraction,
                    weekly?.resetTime,
                    weekly?.countdown ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.CLAUDE } }?.weeklyResetCountdown
                )

                CategoryNavigationTile(
                    title = "Anthropic Claude",
                    activeModelName = if (isClaudeActive) cleanActiveModelName else null,
                    line1 = line1,
                    fraction1 = fiveHourFraction,
                    line2 = line2,
                    fraction2 = weeklyFraction,
                    count = claudeModelsCount,
                    brandColor = ClaudeTerracotta,
                    onClick = { onSelectCategory(CategoryType.CLAUDE) }
                )
            }
        }
    }
}

/**
 * Subcategory Sheet View showing the models inside the chosen family,
 * grouping models that share base names with different thinking tiers (High, Medium, Low).
 */
data class ModelVariant(
    val model: AiModel,
    val tierLabel: String
)

data class SubGroupedModel(
    val baseId: String,
    val baseName: String,
    val description: String,
    val family: ModelFamily,
    val variants: List<ModelVariant>
)

private fun groupModelsByBaseName(models: List<AiModel>): List<SubGroupedModel> {
    // Group real thinking tiers of one model: their keys are equal once the tier suffix is
    // stripped. Grouping by display name instead is wrong, because the hub hands the same
    // name to *different* models: 3.5 Flash Lite, 2.5 Flash Lite, 2.5 Flash Thinking and
    // 2.5 Flash all arrive as "Gemini 3.5 Flash Lite".
    val map = LinkedHashMap<String, MutableList<AiModel>>()
    for (model in models) {
        map.getOrPut(keyWithoutTier(model)) { mutableListOf() }.add(model)
    }

    // Display names the hub reuses for more than one model, compared after trimming so a
    // stray space cannot hide a duplicate.
    val nameCounts = models.groupingBy { normalizedName(it) }.eachCount()

    val groups = mutableListOf<SubGroupedModel>()
    for ((baseKey, tierModels) in map) {
        val first = tierModels.first()
        val nameIsShared = (nameCounts[normalizedName(first)] ?: 0) > 1

        if (tierModels.size > 1) {
            // when one of the tiers is the plain model, name the card after it, not after
            // "… Thinking", which would then also be the label of a pill
            val titleSource = tierModels.firstOrNull { explicitTier(it) == null } ?: first
            groups += SubGroupedModel(
                baseId = baseKey,
                // models that share a display name can also share their base key here
                // (2.5 Flash and 2.5 Flash Thinking), so derive that title from the key
                baseName = if (nameIsShared) nameFromKey(titleSource, models) else cleanBaseName(titleSource),
                description = first.description,
                family = first.family,
                variants = tierModels.map { model ->
                    ModelVariant(model, explicitTier(model) ?: "Default")
                }
            )
        } else {
            val model = first
            groups += SubGroupedModel(
                baseId = baseKey,
                // the hub reuses one display name for different models; only then fall back
                // to the model key, otherwise keep the hub's name, including its tier
                baseName = if (nameIsShared) nameFromKey(model, models) else model.displayName,
                description = model.description,
                family = model.family,
                variants = listOf(ModelVariant(model, ""))
            )
        }
    }
    return groups
}

private val TIER_SUFFIX = Regex(
    "(?:^|[_\\-])(high|medium|med|low|extra[_\\-]?low|thinking)$",
    RegexOption.IGNORE_CASE
)

/** The thinking tier a model declares, either in its display name or as a model-key suffix. */
private fun explicitTier(model: AiModel): String? {
    model.tier?.takeIf { it.isNotBlank() }?.let { return it }
    val match = TIER_SUFFIX.find(model.key.ifBlank { model.id }) ?: return null
    return match.groupValues[1]
        .replace('_', ' ')
        .split(' ')
        .joinToString(" ") { part ->
            part.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
        }
}

private fun keyWithoutTier(model: AiModel): String {
    val key = model.key.ifBlank { model.id }
    val match = TIER_SUFFIX.find(key) ?: return key
    return key.substring(0, match.range.first).trim('-', '_', ' ')
}

/** The display name with surrounding and repeated whitespace removed, for comparisons. */
private fun normalizedName(model: AiModel): String =
    model.displayName.trim().replace(Regex("\\s+"), " ")

private fun cleanBaseName(model: AiModel): String =
    model.baseName.ifBlank {
        model.displayName
            .replace(Regex("\\s*\\((High|Medium|Low|Med|Thinking)\\)", RegexOption.IGNORE_CASE), "")
            .trim()
    }

/**
 * A name built from the part of the model key a group of same-named models does not share,
 * used when the hub gives several models the same display name:
 * `MODEL_GOOGLE_GEMINI_2_5_FLASH_LITE` next to `MODEL_GOOGLE_GEMINI_3_5_FLASH_LITE` becomes
 * "Gemini 2.5 Flash Lite" / "Gemini 3.5 Flash Lite".
 */
private fun nameFromKey(model: AiModel, allModels: List<AiModel>): String {
    val name = normalizedName(model)
    val peers = allModels
        .filter { normalizedName(it) == name }
        .map { it.key.ifBlank { it.id } }
        .ifEmpty { listOf(model.key.ifBlank { model.id }) }
    val sharedPrefix = peers.reduce { acc, next -> acc.commonPrefixWith(next) }
    val tokens = model.key.ifBlank { model.id }
        .removePrefix(sharedPrefix)
        .split('-', '_', ' ')
        .filter { it.isNotBlank() }
    if (tokens.isEmpty()) return name

    val words = mutableListOf<String>()
    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        val next = tokens.getOrNull(index + 1)
        if (token.length == 1 && token[0].isDigit() && next != null && next.length == 1 && next[0].isDigit()) {
            words += "$token.$next"
            index += 2
        } else {
            words += when {
                token in KEY_ACRONYMS -> token
                // keep "120B", "4o" and plain numbers as they are
                token.any { it.isDigit() } -> token
                else -> token.lowercase().replaceFirstChar { it.titlecase() }
            }
            index++
        }
    }

    val derived = words.joinToString(" ")
    val familyWord = name.split(' ').firstOrNull { it.length > 2 }.orEmpty()
    return when {
        derived.isBlank() -> name
        familyWord.isBlank() -> derived
        derived.startsWith(familyWord, ignoreCase = true) -> derived
        else -> "$familyWord $derived"
    }
}

/** Key words that must stay upper case when a display name is rebuilt from a model key. */
private val KEY_ACRONYMS = setOf("GPT", "OSS", "AI", "API", "XL", "TTS", "STT")

@Composable
private fun CategoryModelsSubView(
    title: String,
    brandColor: Color,
    models: List<AiModel>,
    selectedModelId: String,
    onBack: () -> Unit,
    onSelectModel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBack()
    }

    val groupedModels = remember(models) { groupModelsByBaseName(models) }

    Column(
        modifier = modifier
            .padding(bottom = 16.dp)
    ) {
        // Compact Back Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to categories",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (groupedModels.size == models.size) {
                        "${models.size} models"
                    } else {
                        "${models.size} models • ${groupedModels.size} groups"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(groupedModels, key = { it.baseId }) { group ->
                if (group.variants.size > 1) {
                    TieredModelGroupCard(
                        group = group,
                        selectedModelId = selectedModelId,
                        onSelectModel = onSelectModel
                    )
                } else {
                    val model = group.variants.first().model
                    ModelRowItem(
                        model = model,
                        title = group.baseName,
                        isSelected = model.id == selectedModelId,
                        onSelect = { onSelectModel(model.id) }
                    )
                }
            }
        }
    }
}

/**
 * Multi-tier model card allowing direct selection of thinking effort (High, Medium, Low).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TieredModelGroupCard(
    group: SubGroupedModel,
    selectedModelId: String,
    onSelectModel: (String) -> Unit
) {
    val isAnyVariantSelected = group.variants.any { it.model.id == selectedModelId }
    val brandColor = when (group.family) {
        ModelFamily.CLAUDE -> ClaudeTerracotta
        ModelFamily.GEMINI -> GeminiBlue
        ModelFamily.OTHER -> MaterialTheme.colorScheme.primary
    }

    val isDark = isAppInDarkTheme()
    val cardBg = if (isAnyVariantSelected) {
        if (isDark) ClaudeDarkSurface else Color.White
    } else {
        if (isDark) ClaudeDarkSurface.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.85f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .clip(RoundedCornerShape(12.dp)),
        color = cardBg,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.baseName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                if (isAnyVariantSelected) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(brandColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Active",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = brandColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Thinking Effort Selector Pills. A FlowRow, not a Row: a model with four tiers
            // used to overflow the row, and the last pill was measured at zero width, which
            // wrapped its text one character per line and stretched the whole card.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                group.variants.forEach { variant ->
                    val isSelected = variant.model.id == selectedModelId
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectModel(variant.model.id) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) brandColor else brandColor.copy(alpha = 0.12f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                            }
                            Text(
                                text = variant.tierLabel.ifBlank { variant.model.displayName },
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clickable Category Tile with forward arrow navigating to the sub-sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryNavigationTile(
    title: String,
    activeModelName: String? = null,
    line1: String?,
    fraction1: Float?,
    line2: String? = null,
    fraction2: Float? = null,
    count: Int,
    brandColor: Color,
    onClick: () -> Unit
) {
    val isDark = isAppInDarkTheme()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 88.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        color = if (isDark) ClaudeDarkSurface else Color.White,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(brandColor.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = "$count",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = brandColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                if (activeModelName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Active",
                            tint = brandColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = activeModelName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = brandColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        text = "Tap to choose model",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }

                if (!line1.isNullOrBlank() || !line2.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (!line1.isNullOrBlank()) {
                            QuotaBadgeChip(text = line1, fraction = fraction1)
                        }
                        if (!line2.isNullOrBlank()) {
                            QuotaBadgeChip(text = line2, fraction = fraction2)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = "Open $title",
                tint = if (activeModelName != null) brandColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * Individual selectable Model Row inside a chosen category.
 */
@Composable
private fun ModelRowItem(
    model: AiModel,
    title: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val brandColor = if (model.family == ModelFamily.CLAUDE) ClaudeTerracotta else GeminiBlue

    val isDark = isAppInDarkTheme()
    val cardBg = if (isSelected) {
        if (isDark) ClaudeDarkSurface else Color.White
    } else {
        if (isDark) ClaudeDarkSurface.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.85f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect() },
        color = cardBg,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title.ifBlank { model.displayName },
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(brandColor.copy(alpha = 0.15f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = brandColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Active",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = brandColor
                        )
                    }
                }
            }
        }
    }
}
