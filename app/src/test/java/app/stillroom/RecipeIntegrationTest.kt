package app.stillroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stillroom.data.*
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class RecipeIntegrationTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    @Test fun deniedImageCannotReappearFromCacheOffline() = runBlocking(Dispatchers.IO) {
        val address = ServerAddress.parse("https://example.org")
        val id = AccountId.of(address, 1)
        AccountDatabase.delete(context, id, "image_denial")
        val db = AccountDatabase(context, id, "image_denial")
        try {
            val cache = CachedGrocyRepository(db, address, "synthetic")
            val stock = GrocyStockRepository(setOf("ADMIN"), cache) { emptyList() }
            var status = 200
            val repo = GrocyRecipeRepository(setOf("ADMIN"), db, cache, ManageStock(stock),
                ManageShopping(GrocyShoppingRepository(db, cache, stock, setOf("ADMIN"))), files = { _, _ ->
                    when (status) { 200 -> byteArrayOf(1, 2); 403 -> throw GrocyFailure(403); else -> error("Offline") }
                })
            assertArrayEquals(byteArrayOf(1, 2), repo.image("private.jpg", null))
            status = 403
            try { repo.image("private.jpg", null); fail("Denied image") } catch (_: GrocyFailure) { }
            status = 503
            try { repo.image("private.jpg", null); fail("Revoked image reappeared offline") } catch (_: GrocyFailure) { }
            assertEquals("true", db.get("background", "access-denied"))
        } finally { db.close(); AccountDatabase.delete(context, id, "image_denial") }
    }
    @Test fun deniedKeysDoNotReadAndOfflineEditsRemainPending()=runBlocking(Dispatchers.IO) {
        val server=okhttp3.mockwebserver.MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        val address=ServerAddress.parse("http://127.0.0.1:${server.port}",true);val id=AccountId.of(address,1)
        AccountDatabase.delete(context,id,"recipes_offline");val db=AccountDatabase(context,id,"recipes_offline")
        try {
            val cache=CachedGrocyRepository(db,address,"private",MutationTransport(300));val stockRepo=GrocyStockRepository(setOf("ADMIN"),cache){emptyList()}
            val shopping=ManageShopping(GrocyShoppingRepository(db,cache,stockRepo,setOf("ADMIN")))
            val denied=GrocyRecipeRepository(setOf("CHORES"),db,cache,ManageStock(stockRepo),shopping)
            try { denied.snapshot();fail("Denied read") }catch(_:IllegalStateException){}
            try { denied.save("recipes",null,buildJsonObject { put("name","x") });fail("Denied write") }catch(_:IllegalStateException){}
            assertEquals(0,server.requestCount)
            val repo=GrocyRecipeRepository(setOf("RECIPES"),db,cache,ManageStock(stockRepo),shopping)
            val paths=listOf("recipes","recipes_pos","products","quantity_units","quantity_unit_conversions_resolved")
            paths.forEach { server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("[]")) }
            assertFalse(repo.snapshot().stale);repeat(paths.size){server.takeRequest()}
            paths.forEach { server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503)) }
            assertTrue(repo.snapshot().stale);repeat(paths.size){server.takeRequest()}
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(403))
            try { repo.snapshot();fail("Denied read used cache") }catch(e:GrocyFailure){assertEquals(403,e.status)}
            server.takeRequest()
            val op=repo.save("recipes",2,buildJsonObject { put("name","Edited offline");put("base_servings",2) })
            assertEquals("pending",db.operation(op)!!.state)
            try { repo.save("recipes",2,buildJsonObject { put("name","Duplicate") });fail("Unconfirmed overwrite") }catch(_:IllegalStateException){}
            server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
            repo.sync();assertEquals("needs-review",db.operation(op)!!.state);assertEquals("needs-review",repo.operations().single().state)
            val count=server.requestCount;repo.sync();assertEquals(count,server.requestCount)
            try { repo.save("recipes",null,buildJsonObject { put("name","") });fail("Blank name") }catch(_:IllegalArgumentException){}
            try { repo.save("recipes",null,buildJsonObject { put("unknown",1) });fail("Unknown field") }catch(_:IllegalArgumentException){}
            try { repo.delete("meal_plan",2);fail("Meal denial") }catch(_:IllegalStateException){}
            try { repo.image("../private");fail("Image traversal") }catch(_:IllegalArgumentException){}
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{}"))
            val delete=repo.delete("recipes",3);repo.sync();assertEquals("confirmed",db.operation(delete)!!.state)
        }finally { db.close();AccountDatabase.delete(context,id,"recipes_offline");server.shutdown() }
    }
    @Test fun localGrocyScaleMissingConsumeAndWebRecords()=runBlocking(Dispatchers.IO) {
        val path=System.getenv("STILLROOM_STAGE10_FIXTURES")
        Assume.assumeTrue("Existing local Grocy credentials supplied by stage:10",path!=null)
        val f=Json.parseToJsonElement(File(path!!).readText()).jsonObject["fixtures"]!!.jsonArray.first().jsonObject
        val address=ServerAddress.parse(f.recipeText("base_url"),true);val key=f.recipeText("parent_key");val transport=MutationTransport()
        suspend fun http(method:String,path:String,body:String="{}"):JsonElement {
            val (status,payload)=transport.request(address,key,method,path,body)
            assertTrue("Grocy $method $path HTTP $status",status in 200..299)
            return if(payload.isBlank())JsonObject(emptyMap()) else Json.parseToJsonElement(payload)
        }
        val created=mutableListOf<Pair<String,Long>>()
        suspend fun create(entity:String,fields:JsonObject):Long {
            val id=http("POST","/objects/$entity",fields.toString()).jsonObject.recipeId("created_object_id")!!;created+=entity to id;return id
        }
        val token=java.util.UUID.randomUUID().toString().take(8)
        val id=AccountId.of(address,1);AccountDatabase.delete(context,id,"recipes_live");val db=AccountDatabase(context,id,"recipes_live")
        var product:Long?=null;var imageName:String?=null
        try {
            val unit=create("quantity_units",buildJsonObject { put("name","Stage10 unit $token") })
            val large=create("quantity_units",buildJsonObject { put("name","Stage10 double $token") })
            val location=create("locations",buildJsonObject { put("name","Stage10 shelf $token") })
            product=create("products",buildJsonObject { put("name","Stage10 ingredient $token");put("location_id",location);for(k in listOf("qu_id_stock","qu_id_purchase","qu_id_consume","qu_id_price"))put(k,unit) })
            create("quantity_unit_conversions",buildJsonObject { put("product_id",product!!);put("from_qu_id",large);put("to_qu_id",unit);put("factor",2) })
            val list=create("shopping_lists",buildJsonObject { put("name","Stage10 chosen list $token") })
            val cache=CachedGrocyRepository(db,address,key)
            val stockRepo=GrocyStockRepository(setOf("ADMIN"),cache) { emptyList() };val stock=ManageStock(stockRepo)
            val shopping=ManageShopping(GrocyShoppingRepository(db,cache,stockRepo,setOf("ADMIN")))
            val recipes=ManageRecipes(GrocyRecipeRepository(setOf("ADMIN"),db,cache,stock,shopping,files=RecipeFiles(address,key)::request))
            suspend fun confirmed(op:String) { recipes.sync();assertEquals("confirmed",db.operation(op)!!.state) }
            val createOp=recipes.save("recipes",null,buildJsonObject { put("name","Stage10 recipe $token");put("base_servings",2);put("description",recipeDescription("Mix ingredients.\nCook carefully.","https://example.org/recipe")) })
            confirmed(createOp)
            val recipe=db.operation(createOp)!!.responsePayload!!.let { Json.parseToJsonElement(it).jsonObject.recipeId("created_object_id")!! };created+="recipes" to recipe
            val add=recipes.save("recipes_pos",null,buildJsonObject { put("recipe_id",recipe);put("product_id",product!!);put("amount",Json.parseToJsonElement("1.25"));put("qu_id",large);put("note","Synthetic ingredient") });confirmed(add)
            val ingredient=Json.parseToJsonElement(db.operation(add)!!.responsePayload!!).jsonObject.recipeId("created_object_id")!!
            val purchase=stock.book(StockBooking(StockAction.Purchase,product!!,BigDecimal("3"),location=location,date="2027-01-01"));confirmed(purchase)
            val scale=recipes.save("recipes",recipe,buildJsonObject { put("desired_servings",4) });confirmed(scale)
            val base=http("GET","/objects/recipes_pos/$ingredient").jsonObject.recipeDecimal("amount")!!
            assertEquals(0,BigDecimal("1.25").compareTo(base))
            assertEquals("4",http("GET","/objects/recipes/$recipe").jsonObject.recipeText("desired_servings"))
            val reviewMissing=recipes.review(recipe);val missing=reviewMissing.lines.single()
            assertEquals(0,BigDecimal("5").compareTo(missing.required));assertEquals(0,BigDecimal("2").compareTo(missing.missing));assertFalse(missing.canConsume)
            try { recipes.consume(reviewMissing);fail("Missing stock consumed") }catch(_:IllegalStateException){}
            val shoppingOps=recipes.missing(recipe,list);assertEquals(1,shoppingOps.size);recipes.sync()
            val shoppingRows=http("GET","/objects/shopping_list").jsonArray.map { it.jsonObject }.filter { it.recipeId("product_id")==product }
            assertEquals(1,shoppingRows.size);assertEquals(list,shoppingRows.single().recipeId("shopping_list_id"));assertEquals(0,BigDecimal("2").compareTo(shoppingRows.single().recipeDecimal("amount")))
            assertTrue(recipes.missing(recipe,list).isEmpty()) // Repeating adds only the unmet amount.
            // The web form stores amount in stock units even when qu_id selects a display unit.
            // External edit: one stock unit remains on the list, displayed as half a double unit.
            val shoppingRow = shoppingRows.single().recipeId("id")!!
            http("PUT", "/objects/shopping_list/$shoppingRow", buildJsonObject { put("amount", 1); put("qu_id", large) }.toString())
            val remainder = recipes.missing(recipe, list)
            assertEquals("Display-unit conversion must not be applied twice", 1, remainder.size)
            recipes.sync()
            val outstanding = http("GET", "/objects/shopping_list").jsonArray.map { it.jsonObject }
                .filter { it.recipeId("product_id") == product && it.recipeId("shopping_list_id") == list }
            assertEquals(0, BigDecimal("2").compareTo(outstanding.sumOf { it.recipeDecimal("amount")!! }))
            assertTrue(recipes.missing(recipe, list).isEmpty())
            val refill=stock.book(StockBooking(StockAction.Purchase,product!!,BigDecimal("2"),location=location,date="2027-01-01"));confirmed(refill)
            val review=recipes.review(recipe)
            val consume=recipes.consume(review);assertEquals(1,consume.size);assertEquals(consume,recipes.consume(review));recipes.sync();assertEquals("confirmed",db.operation(consume.single())!!.state)
            assertEquals(consume,recipes.consume(review));recipes.sync()
            assertEquals("0",http("GET","/stock/products/$product").jsonObject.recipeText("stock_amount"))
            val logs=http("GET","/objects/stock_log").jsonArray.map { it.jsonObject }.filter { it.recipeId("product_id")==product && it.recipeText("transaction_type")=="consume" }
            assertEquals(1,logs.size);assertEquals(recipe,logs.single().recipeId("recipe_id"));assertEquals(0,BigDecimal("-5").compareTo(logs.single().recipeDecimal("amount")))
            assertEquals(0,base.compareTo(http("GET","/objects/recipes_pos/$ingredient").jsonObject.recipeDecimal("amount")))
            val undo=stock.undo(logs.single().recipeId("id")!!);confirmed(undo);assertEquals("5",http("GET","/stock/products/$product").jsonObject.recipeText("stock_amount"))
            val section=recipes.snapshot().rows("meal_plan_sections").first().recipeId("id")!!
            val meal=recipes.save("meal_plan",null,buildJsonObject { put("day","2027-01-02");put("type","recipe");put("recipe_id",recipe);put("recipe_servings",Json.parseToJsonElement("1.5"));put("section_id",section);put("note","Synthetic meal") });confirmed(meal)
            val mealId=Json.parseToJsonElement(db.operation(meal)!!.responsePayload!!).jsonObject.recipeId("created_object_id")!!
            assertEquals(0,BigDecimal("1.5").compareTo(http("GET","/objects/meal_plan/$mealId").jsonObject.recipeDecimal("recipe_servings")))
            confirmed(recipes.save("meal_plan",mealId,buildJsonObject { put("day","2027-01-03");put("type","recipe");put("recipe_id",recipe);put("recipe_servings",2);put("section_id",section);put("note","Edited") }))
            confirmed(recipes.delete("meal_plan",mealId))
            val png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3ioAAAAASUVORK5CYII=")
            imageName="stage10-$token.png";recipes.image(imageName!!,png)
            confirmed(recipes.save("recipes",recipe,buildJsonObject { put("picture_file_name",imageName!!) }))
            assertTrue(png.contentEquals(recipes.image(imageName!!)))
            assertEquals("https://example.org/recipe",recipeSource(http("GET","/objects/recipes/$recipe").jsonObject.recipeText("description")))
            confirmed(recipes.save("recipes_pos",ingredient,buildJsonObject { put("recipe_id",recipe);put("product_id",product!!);put("amount",Json.parseToJsonElement("0.3333333333333333"));put("qu_id",unit);put("note","Edited") }))
            confirmed(recipes.delete("recipes_pos",ingredient))
            confirmed(recipes.save("recipes",recipe,buildJsonObject { put("name","Stage10 edited recipe $token") }))
            confirmed(recipes.delete("recipes",recipe));created.remove("recipes" to recipe)
            val imported=recipes.createImport(buildJsonObject { put("name","Stage10 mapped import $token");put("base_servings",2);put("description",recipeDescription("Review then cook.","https://example.org/import")) },listOf(buildJsonObject { put("product_id",product!!);put("qu_id",unit);put("amount",Json.parseToJsonElement("0.5"));put("note","Explicit mapping") }))
            confirmed(imported);recipes.sync()
            val importedId=Json.parseToJsonElement(db.operation(imported)!!.responsePayload!!).jsonObject.recipeId("created_object_id")!!
            val positions=http("GET","/objects/recipes_pos").jsonArray.map { it.jsonObject }.filter { it.recipeId("recipe_id")==importedId }
            assertEquals(1,positions.size);assertEquals(product,positions.single().recipeId("product_id"))
            confirmed(recipes.delete("recipes",importedId))
            File("build/reports/stage10").mkdirs();File("build/reports/stage10/local-parity.json").writeText("{\"grocy_version\":\"${f.recipeText("version")}\",\"scaled_required_stock_units\":5,\"missing_added_chosen_list\":2,\"consumed_stock_units\":5,\"consume_journal_rows\":1,\"base_amount_unchanged\":true,\"meal_crud_images_source_undo\":true}\n")
        } finally {
            product?.let { p->http("GET","/objects/shopping_list").jsonArray.map { it.jsonObject }.filter { it.recipeId("product_id")==p }.forEach { http("DELETE","/objects/shopping_list/${it.recipeId("id")}") } }
            for((entity,row)in created.asReversed())http("DELETE","/objects/$entity/$row")
            imageName?.let { name->val encoded=java.util.Base64.getEncoder().encodeToString(name.toByteArray());http("DELETE","/files/recipepictures/$encoded") }
            db.close();AccountDatabase.delete(context,id,"recipes_live")
        }
    }
}
