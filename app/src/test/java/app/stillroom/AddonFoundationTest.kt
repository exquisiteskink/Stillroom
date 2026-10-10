package app.stillroom

import app.stillroom.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AddonFoundationTest {
    @Test fun flagsNeverGrantPermissionsAndUnknownFlagsRemainAvailable() {
        val c=ServerCapabilities.parse(Json.parseToJsonElement("""{"FEATURE_FLAG_STOCK":false,"FEATURE_FLAG_TASKS":"0","FEATURE_FLAG_RECIPES":true}""").jsonObject,JsonObject(emptyMap()))
        assertFalse(c.enabled("STOCK"));assertFalse(c.enabled("TASKS"));assertTrue(c.enabled("RECIPES"));assertTrue(c.enabled("CHORES"))
        assertFalse(ServerCapabilities(disabled=setOf("SHOPPINGLIST")).allows("/stock/shoppinglist/add-missing-products"));assertFalse(c.allows("/stock/products/3/add"));assertTrue(c.allows("/objects/userfields"))
    }
    @Test fun grocycodeParsingDoesNotTreatPrivateCodesAsPublicBarcodes() {
        assertEquals(Grocycode("p",13,"60bf8b5244b04"),Grocycode.parse("grcy:p:13:60bf8b5244b04"))
        assertEquals(Grocycode("c",7),Grocycode.parse("grcy:c:7"))
        listOf("grcy:p:0","grcy:p:-1","grcy:x:1","grcy:p:1:../../x","grcy:p:1:a:b").forEach { assertNull(Grocycode.parse(it)) }
        assertFalse(ScanCode("grcy:p:13",ScanFormat.Other).publicLookup)
    }
    @Test fun richBarcodeMetadataRetainsExactValuesWithoutInventingDefaults() {
        val row=Json.parseToJsonElement("""{"product_id":"8","qu_id":2,"amount":"0.3333333333333333","shopping_location_id":4,"last_price":"2.50","note":"Case"}""").jsonObject
        val b=BarcodeMetadata.parse(row)!!
        assertEquals(8L,b.productId);assertEquals("0.3333333333333333",b.amount);assertEquals(4L,b.store);assertEquals("2.50",b.price)
        assertNull(BarcodeMetadata.parse(buildJsonObject { put("product_id",0) }))
    }
    @Test fun sideEffectGetsNeverBecomeRefreshResources() {
        assertTrue(refreshableGrocyPath("/stock/products/3/entries"))
        assertTrue(refreshableGrocyPath("/userfields/userentity-Marques/4"))
        listOf("/stock/products/3/printlabel","/print/shoppinglist/thermal","/stock/barcodes/external-lookup/123?add=true","/objects/api_keys","/objects/sessions","/system/config").forEach { assertFalse(it,refreshableGrocyPath(it)) }
    }
    @Test fun capabilitiesMatchTemplatesAndRequireAdvertisedMutationMethods() {
        val spec=Json.parseToJsonElement("""{"paths":{"/objects/{entity}/{objectId}":{"get":{},"put":{}},"/tasks/{taskId}/complete":{"post":{}}}}""").jsonObject
        val c=ServerCapabilities.parse(JsonObject(emptyMap()),spec)
        assertEquals(true,c.requestSupported("PUT","/objects/userobjects/3"));assertEquals(false,c.requestSupported("DELETE","/objects/userobjects/3"))
        assertEquals(true,c.requestSupported("POST","/tasks/4/complete"));assertEquals(false,c.requestSupported("POST","/unknown"))
        assertEquals(true,c.requestSupported("GET","/openapi/specification"));assertNull(ServerCapabilities().requestSupported("POST","/unknown"))
    }
    @Test fun addonUrlsRejectCredentialsAndRequireExplicitHttpOptIn() {
        assertEquals("https://example.org/stocksettings?statnerd=1",validateAddonWebUrl("https://example.org/stocksettings?statnerd=1",false))
        listOf("https://user:secret@example.org/","https://example.org/?%74oken=secret","http://example.org/","javascript:alert(1)").forEach { value->
            try { validateAddonWebUrl(value,false);fail(value) }catch(_:IllegalArgumentException){}
        }
        assertEquals("http://example.org/",validateAddonWebUrl("http://example.org/",true))
        assertEquals(AddonSettings(),AddonSettings.parse(null));assertEquals(AddonSettings(publicLookup=false),AddonSettings.parse(AddonSettings(publicLookup=false).json()))
    }
    @Test fun userfileReferencesRoundTripWithNamesFromGrocysWebApp() {
        val value=UserfileReference("logo.png","Brand logo.png").stored()
        assertEquals(UserfileReference("logo.png","Brand logo.png"),UserfileReference.parse(value))
        assertEquals("old.png",UserfileReference.parse("old.png").storageName)
        assertEquals("bad_%%",UserfileReference.parse("bad_%%").storageName)
    }
    @Test fun stockEntryLabelsKeepGrocyEntryIdentityAndRequireOneEntry() {
        val booking=StockBooking(StockAction.Consume,13,java.math.BigDecimal.ONE,stockEntryId="60bf8b5244b04")
        assertEquals("60bf8b5244b04",booking.payload()["stock_entry_id"]?.jsonPrimitive?.content)
        try { booking.copy(amount=java.math.BigDecimal.TEN).payload();fail("Multiple label entries") }catch(_:IllegalArgumentException){}
    }
    @Test fun barcodePriceCalculationAccountsForNetTareAndPreservesManualUnitPrices() {
        assertEquals(java.math.BigDecimal("2.5"),reviewedStockPrice(java.math.BigDecimal("30"),java.math.BigDecimal("2"),java.math.BigDecimal("6"),true))
        assertEquals(java.math.BigDecimal("3"),reviewedStockPrice(java.math.BigDecimal("30"),java.math.BigDecimal("12"),java.math.BigDecimal.ONE,true,java.math.BigDecimal("2")))
        assertEquals(java.math.BigDecimal("30"),reviewedStockPrice(java.math.BigDecimal("30"),java.math.BigDecimal("2"),java.math.BigDecimal("6"),false))
        try { reviewedStockPrice(java.math.BigDecimal.ONE,java.math.BigDecimal.ONE,java.math.BigDecimal.ONE,true,java.math.BigDecimal.ONE);fail("Zero net tare") }catch(_:IllegalArgumentException){}
    }
    @Test fun temporaryServerNavigationFallbackDoesNotRewriteUserPreferences() {
        val preferences=object:ShellPreferencesRepository {
            override fun load()=ShellPreferences(visibleSections=setOf(Section.Pantry))
            override fun save(preferences:ShellPreferences)=fail("Availability rewrote user preferences")
        }
        val model=app.stillroom.ui.ShellViewModel(GetAppName(AppInfoRepository { "Stillroom" }),ManageShellPreferences(preferences))
        model.selectAvailableSection(Section.Today)
        assertEquals(Section.Today,model.state.value.selectedSection)
        assertEquals(setOf(Section.Pantry),model.state.value.preferences.visibleSections)
    }
}
