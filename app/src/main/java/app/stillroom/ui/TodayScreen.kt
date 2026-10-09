package app.stillroom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.stillroom.domain.Account
import app.stillroom.R
import app.stillroom.domain.HouseholdAccess
import app.stillroom.domain.RecipeAccess
import app.stillroom.domain.Section
import app.stillroom.domain.catalogId
import app.stillroom.domain.catalogText
import app.stillroom.domain.houseDueDay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

@Composable fun TodayScreen(model:TodayViewModel,account:Account,open:(Section)->Unit,openRecipe:(Long)->Unit = {},openMeal:(Long)->Unit = {}) {
    val state by model.state.collectAsState();val s=state.snapshot
    LaunchedEffect(Unit){model.refresh()}
    val today=remember { LocalDate.now() }
    val greeting=remember(today) {
        val weekday=today.dayOfWeek.getDisplayName(TextStyle.FULL,Locale.getDefault())
        val month=today.month.getDisplayName(TextStyle.FULL,Locale.getDefault())
        "$weekday, $month ${today.dayOfMonth}"
    }
    val whisper=when {
        s.accessDenied->"This account cannot see household records until access is verified again."
        state.error!=null->null
        s.missing.isNotEmpty() && !state.busy->"Some records are unavailable. Refresh when you’re online."
        else->null
    }
    KitchenList(state.busy, model::refresh) {
        item {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                val hour = LocalTime.now().hour
                Text(when { hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" },
                    style=MaterialTheme.typography.displaySmall, color=MaterialTheme.colorScheme.onSurface)
                Text(greeting, style=MaterialTheme.typography.bodyLarge, color=MaterialTheme.colorScheme.onSurfaceVariant)
                KitchenWhisper(whisper)
                KitchenError(state.error)
            }
        }
        item { TodayPhoto() }
        if(HouseholdAccess.has(account.permissions,"CHORES")) {
            item { KitchenSectionTitle("Due chores") }
            if(s.chores.isEmpty()) item {
                KitchenCard { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if (state.busy) "Loading chores…" else if (s.account == null || s.missing.any { it.contains("chores") } || s.accessDenied) "Chores unavailable" else "No chores due today", style=MaterialTheme.typography.titleMedium)
                    Text("View other chores in Household.", color=MaterialTheme.colorScheme.onSurfaceVariant)
                    QuietButton(onClick={open(Section.Household)}){Text("View chores")}
                } }
            } else items(s.chores, key={ it.catalogText("chore_id")+it.catalogText("chore_name") }) { chore ->
                KitchenCard(onClick={open(Section.Household)}) {
                    Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(chore.catalogText("chore_name"), style=MaterialTheme.typography.titleMedium)
                        QuantityBadge(houseDueDay(chore.catalogText("next_estimated_execution_time"), Locale.getDefault()) ?: "Due", ColorTone.Due)
                    }
                }
            }
        }
        if(RecipeAccess.mealPlan(account.permissions)) {
            item { KitchenSectionTitle("Planned meals") }
            if(s.meals.isEmpty()) item {
                KitchenCard { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if (state.busy) "Loading meals…" else if (s.account == null || s.missing.any { it.contains("meal_plan") || it.contains("recipes") } || s.accessDenied) "Meal plan unavailable" else "No meals planned today", style=MaterialTheme.typography.titleMedium)
                    Text("Plan a meal in Meals.", color=MaterialTheme.colorScheme.onSurfaceVariant)
                    QuietButton(onClick={open(Section.Meals)}){Text("View meals")}
                } }
            } else items(s.meals, key={ it.catalogText("id")+it.catalogText("recipe_name") }) { row ->
                KitchenCard(onClick={
                    when (row.catalogText("type")) {
                        "recipe" -> row.catalogId("recipe_id")?.let(openRecipe) ?: open(Section.Meals)
                        else -> row.catalogId("id")?.let(openMeal) ?: open(Section.Meals)
                    }
                }) {
                    Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(row.catalogText("section_name").ifBlank { "Meal" }, style=MaterialTheme.typography.labelLarge, color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(row.catalogText("recipe_name").ifBlank { row.catalogText("note").ifBlank { "Planned product" } }, style=MaterialTheme.typography.titleMedium)
                        val servings=row.catalogText("recipe_servings").takeIf { row.catalogText("type")=="recipe" }
                        if(!servings.isNullOrBlank()) Text("$servings servings", color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Welcoming time-of-day photography; household meal records remain separately labeled. */
@Composable internal fun TodayPhoto() {
    val hour = LocalTime.now().hour
    val photo = when { hour < 11 -> R.drawable.today_breakfast; hour < 17 -> R.drawable.today_lunch; else -> R.drawable.today_dinner }
    Image(painterResource(photo), contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().height(200.dp).clip(MaterialTheme.shapes.large))
}
