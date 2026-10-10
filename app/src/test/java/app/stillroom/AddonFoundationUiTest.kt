package app.stillroom

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.stillroom.domain.*
import app.stillroom.ui.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class AddonFoundationUiTest {
    @get:Rule val compose=createComposeRule()
    private val row=Json.parseToJsonElement("""{"id":7,"shopping_list_id":1,"product_id":3,"amount":1.5,"qu_id":1,"done":0}""").jsonObject
    @Test fun externallyOwnedPurchasesOnlyCrossOutTheItem() {
        var saved:ShoppingDraft?=null
        val snapshot=ShoppingSnapshot(mapOf("products" to Json.parseToJsonElement("""[{"id":3,"name":"Apples","qu_id_stock":1}]""").jsonArray),false)
        compose.setContent { StillroomTheme { CompositionLocalProvider(LocalAddonSettings provides AddonSettings(shoppingPurchaseOwner="external")) {
            ShoppingItem(row,row,snapshot,null,false,false,false,{saved=it},{},{fail("Stock booked twice")})
        } } }
        compose.onNodeWithContentDescription("Mark Apples complete").performClick()
        compose.runOnIdle { assertTrue(saved!!.done);assertEquals(BigDecimal("1.5"),saved!!.amount) }
    }
    @Test fun barcodeQuantityAndUnitAreReviewedWithoutLosingPrecision() {
        var booked:StockBooking?=null
        val product=Json.parseToJsonElement("""{"id":3,"qu_id_stock":1,"name":"Milk"}""").jsonObject
        val state=StockUiState(resources=mapOf(
            "/objects/quantity_units" to Json.parseToJsonElement("""[{"id":1,"name":"Bottle"},{"id":2,"name":"Case"}]"""),
            "/objects/quantity_unit_conversions_resolved" to Json.parseToJsonElement("""[{"product_id":3,"from_qu_id":2,"to_qu_id":1,"factor":6}]""")))
        compose.setContent { StillroomTheme { StockForm(state,product,{booked=it},{},setOf("ADMIN"),true,StockAction.Consume,BarcodeMetadata(3,2,"0.3333333333333333")) } }
        compose.onNodeWithText("0.3333333333333333").assertExists()
        compose.onNodeWithText("Confirm use").performClick()
        compose.runOnIdle { assertEquals(BigDecimal("1.9999999999999998"),booked!!.amount.multiply(booked!!.factor)) }
    }
    @Test fun disabledTodayFeaturesDoNotOfferTheirActions() {
        val address=ServerAddress.parse("example.org")
        val account=Account(AccountId.of(address,1),address,1,"Parent","4.7.1",setOf("ADMIN"))
        compose.setContent { StillroomTheme { CompositionLocalProvider(LocalServerCapabilities provides ServerCapabilities(disabled=setOf("TASKS","CHORES","RECIPES"))) {
            TodayContent(TodayUiState(TodaySnapshot(account=account)),account,{},{},{})
        } } }
        compose.onNodeWithText("Tasks").assertDoesNotExist();compose.onNodeWithText("Manage tasks").assertDoesNotExist();compose.onNodeWithText("Due chores").assertDoesNotExist();compose.onNodeWithText("Planned meals").assertDoesNotExist()
    }
    @Test fun barcodeTotalPriceIsConvertedToStockUnitPrice() {
        var booked:StockBooking?=null
        val product=Json.parseToJsonElement("""{"id":3,"qu_id_stock":1,"name":"Milk"}""").jsonObject
        val state=StockUiState(resources=mapOf(
            "/objects/quantity_units" to Json.parseToJsonElement("""[{"id":1,"name":"Bottle"},{"id":2,"name":"Case"}]"""),
            "/objects/quantity_unit_conversions_resolved" to Json.parseToJsonElement("""[{"product_id":3,"from_qu_id":2,"to_qu_id":1,"factor":6}]""")))
        compose.setContent { StillroomTheme { androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) { StockForm(state,product,{booked=it},{},setOf("ADMIN"),true,StockAction.Purchase,BarcodeMetadata(3,2,"2",price="30")) } } }
        compose.onNodeWithText("Total purchase price (optional)").assertExists()
        compose.onNodeWithText("Price per stock unit: 2.5").assertExists()
        compose.onNodeWithContentDescription("Due date (required)").performScrollTo().performTextInput("2026-11-01")
        compose.onNodeWithText("Confirm add").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(BigDecimal("2.5"),booked!!.price);assertEquals(BigDecimal("12"),booked!!.amount.multiply(booked!!.factor)) }
    }
}
