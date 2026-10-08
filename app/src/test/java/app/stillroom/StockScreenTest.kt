package app.stillroom

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.stillroom.ui.StillroomTheme
import app.stillroom.ui.StockJournalEntry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host Compose check. This is not a phone or emulator test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StockScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun consumeJournalAmountDoesNotCrash() {
        val row = buildJsonObject {
            put("product_id", 4); put("amount", -2); put("transaction_type", "consume")
            put("row_created_timestamp", "2026-10-07 12:00:00"); put("best_before_date", "2026-10-08")
            put("price", "1.25"); put("spoiled", 0); put("undone", 0)
        }
        compose.setContent { StillroomTheme { StockJournalEntry(row, "Milk", "Shelf") } }
        compose.onNodeWithText("Milk").assertExists()
        compose.onNodeWithText("Used · -2 · Shelf").assertExists()
    }
}
