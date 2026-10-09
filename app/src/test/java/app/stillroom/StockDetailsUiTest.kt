package app.stillroom

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.stillroom.data.AndroidStockDetailsStore
import app.stillroom.domain.StockDetailSetting
import app.stillroom.ui.KitchenStockRow
import app.stillroom.ui.StillroomTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host-side Robolectric checks; not phone or emulator tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StockDetailsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun storedChoicesAreKeptPerAccount() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val choices = listOf(StockDetailSetting("userfield:note", true), StockDetailSetting("builtin:due", false))
        AndroidStockDetailsStore(context).save("account-a", choices)
        assertEquals(choices, AndroidStockDetailsStore(context).load("account-a"))
        assertNull(AndroidStockDetailsStore(context).load("account-b"))
        AndroidStockDetailsStore(context).clear("account-a")
        assertNull(AndroidStockDetailsStore(context).load("account-a"))
    }

    @Test fun rowShowsChosenExtraLines() {
        compose.setContent { StillroomTheme { KitchenStockRow("Milk", "2 l", onClick = {}, due = "Oct 9", details = listOf("Note: Top shelf", "Organic: Yes")) } }
        compose.onNodeWithText("Milk").assertExists()
        compose.onNodeWithText("2 l").assertExists()
        compose.onNodeWithText("Oct 9").assertExists()
        compose.onNodeWithText("Note: Top shelf").assertExists()
        compose.onNodeWithText("Organic: Yes").assertExists()
    }
}
