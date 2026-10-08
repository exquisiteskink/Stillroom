package app.stillroom.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.stillroom.R
import app.stillroom.data.*
import app.stillroom.domain.*
import app.stillroom.fractions.QuantityFractions
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.util.Locale

private val recipeFractions=QuantityFractions()
@Composable @ReadOnlyComposable private fun qty(amount:BigDecimal)=quantityText(amount)
private fun decimal(text:String)=recipeFractions.parse(text,Locale.getDefault())?.value ?: error("Enter a quantity.")
private fun number(amount:BigDecimal)=Json.parseToJsonElement(amount.toPlainString())

@Composable internal fun RecipeServings(row: JsonObject, busy: Boolean, save: (BigDecimal) -> Unit) {
    val quantities=LocalQuantityFormatter.current
    val input = remember(row) { RecipeAmountInput(row.recipeDecimal("desired_servings") ?: BigDecimal.ONE,Locale.getDefault(),quantities) }
    var servings by remember(row) { mutableStateOf(input.text) }
    LabeledTextField(servings, { servings = it; input.change(it) }, label = "Desired servings", isError=!runCatching { input.saved().signum()>0 }.getOrDefault(false), supportingText=if(!runCatching { input.saved().signum()>0 }.getOrDefault(false)) "Enter servings greater than zero." else null)
    SecondaryButton(onClick = { save(input.saved()) }, enabled = !busy && runCatching { input.saved().signum() > 0 }.getOrDefault(false)) { Text("Scale servings") }
}

@Composable private fun RecipeChoice(label:String,rows:List<JsonObject>,selected:Long?,onSelect:(Long)->Unit) {
    ChoiceField(label, rows.mapNotNull { row -> row.recipeId("id")?.let { it to row.recipeText("name").ifBlank { "Unnamed section" } } }, selected, { it?.let(onSelect) })
}
@Composable private fun RecipeDialog(title:String,close:()->Unit,save:(()->Unit)?=null,valid:Boolean=true,content:@Composable ColumnScope.()->Unit) {
    AlertDialog(onDismissRequest=close,title={Text(title)},text={Column(Modifier.imePadding().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)},
        dismissButton={QuietButton(onClick=close){Text("Cancel")}},confirmButton={if(save!=null)PrimaryButton(onClick=save,enabled=valid){Text("Save")}else QuietButton(onClick=close){Text("Close")}})
}

@Composable fun RecipeScreen(model:RecipeViewModel,account:Account) {
    val state by model.state.collectAsState();val s=state.snapshot
    if(!RecipeAccess.allowed(account.permissions)) { PermissionDeniedState();return }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var mealPlan by rememberSaveable { mutableStateOf(false) }
    var editor by remember { mutableStateOf<JsonObject?>(null) };var newRecipe by remember { mutableStateOf(false) }
    var ingredient by remember { mutableStateOf<JsonObject?>(null) };var newIngredient by remember { mutableStateOf(false) }
    var meal by remember { mutableStateOf<JsonObject?>(null) };var newMeal by remember { mutableStateOf(false) }
    var cooking by rememberSaveable { mutableStateOf(false) }
    var delete by remember { mutableStateOf<Pair<String,Long>?>(null) }
    var list by rememberSaveable { mutableStateOf<Long?>(null) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("") }
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var imageError by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<ByteArray?>(null) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri->
        if(uri!=null)scope.launch {
            try { picked=withContext(Dispatchers.IO) {
                val bytes=context.contentResolver.openInputStream(uri)!!.use { input ->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(out.size()<=5_242_880) { val count=input.read(buffer,0,minOf(buffer.size,5_242_881-out.size()));if(count<0)break;out.write(buffer,0,count) }
                    out.toByteArray()
                }
                require(bytes.size<=5_242_880) { "Choose an image under 5 MB." }
                val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                require(options.outWidth>0 && options.outHeight>0 && options.outWidth.toLong()*options.outHeight<=40_000_000) { "Image is too large." }
                val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("Invalid image.")
                java.io.ByteArrayOutputStream().use { out->bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,out);bitmap.recycle();out.toByteArray().also { require(it.size<=5_242_880) } }
            } }catch(e:CancellationException){throw e}catch(e:Exception){imageError=e.message}
        }
    }
    LaunchedEffect(Unit){model.refresh()}
    LaunchedEffect(s.normal().map { it.recipeText("picture_file_name") }) { model.loadPictures() }
    BackHandler(enabled = cooking || selected != null || importing) {
        when {
            cooking -> cooking = false
            importing -> importing = false
            else -> { selected = null; model.clearImage() }
        }
    }
    val needsReview=state.operations.any { it.state in setOf("needs-review","failed") }
    if(selected==null) {
        KitchenGrid(state.busy, model::refresh) {
            kitchenHeader {
                Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected=!mealPlan,onClick={mealPlan=false},label={Text("Recipes")})
                        if(RecipeAccess.mealPlan(account.permissions))FilterChip(selected=mealPlan,onClick={mealPlan=true},label={Text("Meal plan")})
                    }
                    KitchenWhisper(if(s.stale)"Showing saved recipes. Pull down to reload when you're online." else null)
                    KitchenError(state.error ?: imageError)
                    KitchenWhisper(if(needsReview)"A recipe change needs review. Open Settings → Review pending changes." else null)
                    if(mealPlan) PrimaryButton(onClick={newMeal=true}, modifier=Modifier.fillMaxWidth()){Text("Add meal")}
                    else {
                        PrimaryButton(onClick={newRecipe=true}, modifier=Modifier.fillMaxWidth()){Text("Add recipe")}
                        QuietButton(onClick={importing=!importing}){Text(if(importing) "Cancel import" else "Import recipe")}
                        if(importing) {
                            LabeledTextField(url,{url=it},label = "Recipe link",modifier=Modifier.fillMaxWidth())
                            SecondaryButton(onClick={model.share(url)},enabled=url.isNotBlank() && !state.busy){Text("Review recipe link")}
                        }
                    }
                }
            }
            if(mealPlan) {
                val meals=s.rows("meal_plan").sortedBy { it.recipeText("day")+it.recipeText("section_id") }
                if(state.busy && meals.isEmpty()) kitchenHeader { Text("Loading meal plan…") }
                else if(meals.isEmpty() && state.error!=null) kitchenHeader { ErrorState(state.error!!, model::refresh) }
                else if(meals.isEmpty()) kitchenHeader {
                    KitchenEmpty("No meals planned","Add a recipe, product, or note to the meal plan.", R.drawable.empty_meals)
                } else items(meals, key={ it.recipeText("id") }) { row->
                    val recipeName=when(row.recipeText("type")) {
                        "recipe"->s.name("recipes",row.recipeId("recipe_id"))
                        "product"->s.name("products",row.recipeId("product_id"))
                        else->row.recipeText("note").ifBlank { "Note" }
                    }
                    val pictureName=s.normal().find { it.recipeId("id")==row.recipeId("recipe_id") }?.recipeText("picture_file_name")
                    RecipeTile("${row.recipeText("day")} · ${s.name("meal_plan_sections",row.recipeId("section_id"))}\n${recipeName.ifBlank { "Meal" }}", state.pictures[pictureName.orEmpty()], onClick={
                        if(row.recipeText("type")=="recipe") { selected=row.recipeId("recipe_id");mealPlan=false } else meal=row
                    })
                }
            } else {
                val recipes=s.normal()
                if(state.busy && recipes.isEmpty()) kitchenHeader { Text("Loading recipes…") }
                else if(recipes.isEmpty() && state.error!=null) kitchenHeader { ErrorState(state.error!!, model::refresh) }
                else if(recipes.isEmpty()) kitchenHeader {
                    KitchenEmpty("No recipes","Add a recipe or import a recipe link.", R.drawable.empty_meals)
                } else items(recipes, key={ it.recipeText("id") }) { row->
                    RecipeTile(row.recipeText("name"), state.pictures[row.recipeText("picture_file_name")], onClick={selected=row.recipeId("id")})
                }
            }
        }
    } else {
        val row=s.normal().find { it.recipeId("id")==selected }
        val imageName=row?.recipeText("picture_file_name").orEmpty()
        val picture=state.pictures[imageName]
        LaunchedEffect(imageName,selected) {
            if(imageName.isNotBlank() && picture==null) model.loadImage(imageName)
        }
        KitchenList(state.busy, model::refresh) {
            item { QuietButton(onClick={if(cooking)cooking=false else { selected=null;model.clearImage() }}){Text(if(cooking) "Back to recipe" else "Back to recipes")} }
            if(row==null) item {
                when {
                    state.busy -> Text("Loading recipe…")
                    state.error!=null -> ErrorState(state.error!!, model::refresh)
                    else -> Text("This recipe is no longer available.")
                }
            }
            else if(cooking) {
                item { Text(row.recipeText("name"),style=MaterialTheme.typography.headlineMedium) }
                item { CookingMode(row,s) }
            } else {
                item {
                    val bitmap=remember(picture){picture?.let { android.graphics.BitmapFactory.decodeByteArray(it,0,it.size)?.asImageBitmap() }}
                    if(bitmap!=null) Image(bitmap,row.recipeText("name"),contentScale=ContentScale.Crop,modifier=Modifier.fillMaxWidth().height(220.dp).clip(MaterialTheme.shapes.large))

                }
                item { Text(row.recipeText("name"),style=MaterialTheme.typography.headlineMedium) }
                item {
                    KitchenError(state.error ?: imageError)
                    KitchenWhisper(if(needsReview) "A recipe change needs review in Settings → Pending changes." else if(state.operations.any { it.state in setOf("pending", "in-flight") }) "Recipe change pending confirmation." else null)
                }
                item { PrimaryButton(onClick={cooking=true}, modifier=Modifier.fillMaxWidth()){Text("Start cooking")} }
                item { Row { QuietButton(onClick={editor=row}){Text("Edit")};QuietButton(onClick={delete="recipes" to selected!!}){Text("Delete")} } }
                item { RecipeServings(row, state.busy) { servings -> model.save("recipes", selected, buildJsonObject { put("desired_servings", number(servings)) }) } }
                item { Text("Serves ${qty(row.recipeDecimal("base_servings") ?: BigDecimal.ONE)} as written", color=MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Row { QuietButton(onClick={picker.launch("image/*")},enabled=!state.busy){Text("Choose photo")};if(imageName.isNotBlank())QuietButton(onClick={model.save("recipes",selected,buildJsonObject { put("picture_file_name",JsonNull) })}){Text("Remove photo")} } }
                item { KitchenSectionTitle("Ingredients") }
                s.rows("recipes_pos").filter { it.recipeId("recipe_id")==selected }.forEach { pos->
                    val resolved=s.rows("recipes_pos_resolved").find { it.recipeId("recipe_id")==selected && it.recipeId("recipe_pos_id")==pos.recipeId("id") && it.recipeText("is_nested_recipe_pos")!="1" }
                    item {
                        KitchenCard {
                            Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("${qty(resolved?.recipeDecimal("recipe_amount") ?: pos.recipeDecimal("amount") ?: BigDecimal.ZERO)} ${s.name("quantity_units",pos.recipeId("qu_id"))} ${s.name("products",pos.recipeId("product_id"))}", style=MaterialTheme.typography.titleMedium)
                                if(pos.recipeText("note").isNotBlank()) Text(pos.recipeText("note"), color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(resolved!=null) {
                                    val product=resolved.recipeId("product_id_effective") ?: pos.recipeId("product_id")
                                    val stockUnit=s.rows("products").find { it.recipeId("id")==product }?.recipeId("qu_id_stock")
                                    val available=s.rows("stock").filter { it.recipeId("product_id")==product }.sumOf { it.recipeDecimal("amount") ?: BigDecimal.ZERO }
                                    QuantityBadge("In the pantry: ${qty(available)} ${s.name("quantity_units",stockUnit)}", ColorTone.Stocked)
                                }
                                Row { QuietButton(onClick={ingredient=pos}){Text("Edit")};QuietButton(onClick={delete="recipes_pos" to pos.recipeId("id")!!}){Text("Remove")} }
                            }
                        }
                    }
                }
                item { QuietButton(onClick={newIngredient=true}){Text("Add ingredient")} }
                if(ShoppingAccess.allowed(account.permissions) && StockAccess.canRead(account.permissions)) item {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        RecipeChoice("Shopping list",s.rows("shopping_lists"),list){list=it}
                        SecondaryButton(onClick={model.missing(selected!!,list!!)},enabled=list!=null && !state.busy, modifier=Modifier.fillMaxWidth()){Text("Add missing ingredients")}
                    }
                }
                if(StockAccess.canWrite(account.permissions,StockAction.Consume)) item {
                    SecondaryButton(onClick={model.review(selected!!)},enabled=!state.busy, modifier=Modifier.fillMaxWidth()){Text("Use recipe ingredients")}
                }
                recipeSteps(row.recipeText("description")).forEach { step -> item { Text(step, style=MaterialTheme.typography.bodyLarge) } }
                recipeSource(row.recipeText("description")).takeIf { it.isNotBlank() }?.let { source -> item { Text("From $source", color=MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
        }
    }
    if(newRecipe || editor!=null)RecipeEditor(editor,{newRecipe=false;editor=null}) { fields->model.save("recipes",editor?.recipeId("id"),fields);newRecipe=false;editor=null }
    if(newIngredient || ingredient!=null)IngredientEditor(s,selected!!,ingredient,{newIngredient=false;ingredient=null}) { fields->model.save("recipes_pos",ingredient?.recipeId("id"),fields);newIngredient=false;ingredient=null }
    if(newMeal || meal!=null)MealEditor(s,meal,{newMeal=false;meal=null}) { fields->model.save("meal_plan",meal?.recipeId("id"),fields);newMeal=false;meal=null }
    delete?.let { (entity,id)->AlertDialog(onDismissRequest={delete=null},title={Text(if(entity=="recipes") "Delete recipe?" else if(entity=="recipes_pos") "Remove ingredient?" else "Delete meal?")},text={Text("This deletes the record in Grocy.")},dismissButton={QuietButton(onClick={delete=null}){Text("Cancel")}},confirmButton={PrimaryButton(onClick={model.delete(entity,id);delete=null}){Text("Delete")}}) }
    picked?.let { bytes->RecipeDialog("Review image",{picked=null},save={selected?.let { model.attachImage(it,bytes) };picked=null}) { Text("Save this selected image to Grocy?");val bmp=remember(bytes){android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)};if(bmp!=null)Image(bmp.asImageBitmap(),"Selected image",Modifier.heightIn(max=220.dp)) } }
    state.review?.let { review->AlertDialog(onDismissRequest=model::closeReview,title={Text("Review consumption")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("${qty(review.servings)} servings · stock units")
        review.lines.forEach { line->Text("${s.name("products",line.product)}: consume ${qty(line.required)} ${s.name("quantity_units",line.unit)} · available ${qty(line.available)}") }
        Text("This records each ingredient used. Check Pending changes for confirmation; use Pantry history to undo a confirmed change.")
    }},dismissButton={QuietButton(onClick=model::closeReview){Text("Cancel")}},confirmButton={PrimaryButton(onClick=model::consume,enabled=!state.busy && review.lines.all { it.canConsume }){Text("Use ingredients")}}) }
    state.imported?.let { draft->ImportRecipeReview(draft,s,state.busy,{model.loadUrl(draft.source)},model::closeImport,model::import) }
}

@Composable private fun RecipeEditor(row:JsonObject?,close:()->Unit,save:(JsonObject)->Unit) {
    val quantities=LocalQuantityFormatter.current
    var name by remember { mutableStateOf(row?.recipeText("name").orEmpty()) }
    val original=row?.recipeText("description").orEmpty()
    var instructions by remember { mutableStateOf(recipeSteps(original).joinToString("\n")) };var changed by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf(recipeSource(original)) }
    val amount=remember { RecipeAmountInput(row?.recipeDecimal("base_servings") ?: BigDecimal.ONE,Locale.getDefault(),quantities) };var servings by remember { mutableStateOf(amount.text) }
    val valid=name.isNotBlank() && runCatching { amount.saved().signum()>0 && (source.isBlank() || recipeDescription("",source).isNotBlank()) }.getOrDefault(false)
    RecipeDialog(if(row==null)"Create recipe" else "Edit recipe",close,save={save(buildJsonObject {
        put("name",name);put("base_servings",number(amount.saved()));put("description",if(!changed && source==recipeSource(original))original else recipeDescription(instructions,source))
    })},valid=valid) {
        LabeledTextField(name,{name=it},label = "Name", isError=name.isBlank(), supportingText=if(name.isBlank()) "Enter a recipe name." else null)
        LabeledTextField(servings,{servings=it;amount.change(it)},label = "Base servings", isError = !runCatching { amount.saved().signum()>0 }.getOrDefault(false), supportingText = if(!runCatching { amount.saved().signum()>0 }.getOrDefault(false)) "Enter servings greater than zero." else null)
        LabeledTextField(instructions,{instructions=it;changed=true},label = "Instructions · one step per line")
        LabeledTextField(source,{source=it},label = "Source URL")
        Text("Ingredient amounts stay unchanged until their own Save.")
    }
}
@Composable private fun IngredientEditor(s:RecipeSnapshot,recipe:Long,row:JsonObject?,close:()->Unit,save:(JsonObject)->Unit) {
    val quantities=LocalQuantityFormatter.current
    var product by remember { mutableStateOf(row?.recipeId("product_id")) };var unit by remember { mutableStateOf(row?.recipeId("qu_id")) }
    val amount=remember { RecipeAmountInput(row?.recipeDecimal("amount") ?: BigDecimal.ONE,Locale.getDefault(),quantities) };var text by remember { mutableStateOf(amount.text) }
    var note by remember { mutableStateOf(row?.recipeText("note").orEmpty()) };var group by remember { mutableStateOf(row?.recipeText("ingredient_group").orEmpty()) }
    var variable by remember { mutableStateOf(row?.recipeText("variable_amount").orEmpty()) }
    var single by remember { mutableStateOf(row?.recipeText("only_check_single_unit_in_stock")=="1") };var skip by remember { mutableStateOf(row?.recipeText("not_check_stock_fulfillment")=="1") };var round by remember { mutableStateOf(row?.recipeText("round_up")=="1") }
    RecipeDialog("Ingredient",close,save={save(buildJsonObject {
        put("recipe_id",recipe);put("product_id",product!!);put("qu_id",unit!!);put("amount",number(amount.saved()));put("note",note);put("ingredient_group",group)
        put("only_check_single_unit_in_stock",if(single)1 else 0);put("not_check_stock_fulfillment",if(skip)1 else 0);put("round_up",if(round)1 else 0);put("variable_amount",variable.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull)
    })},valid=product!=null && unit!=null && s.factor(product!!,unit!!)!=null && runCatching { amount.saved().signum()>=0 }.getOrDefault(false)) {
        RecipeChoice("Existing product",s.rows("products"),product){product=it;unit=null}
        RecipeChoice("Unit",s.rows("quantity_units").filter { product!=null && it.recipeId("id")?.let { u->s.factor(product!!,u)!=null }==true },unit){unit=it}
        LabeledTextField(text,{text=it;amount.change(it)},label = "Amount for base servings")
        LabeledTextField(note,{note=it},label = "Ingredient instructions");LabeledTextField(group,{group=it},label = "Ingredient group")
        RecipeToggle("Check one unit in stock",single){single=it};RecipeToggle("Skip stock availability check",skip){skip=it};RecipeToggle("Round up scaled amount",round){round=it}
        LabeledTextField(variable,{variable=it},label = "Variable amount label (optional)")
        if(variable.isNotBlank())Text("Resolve variable quantities to measured amounts before stock consumption.")
    }
}
@Composable private fun MealEditor(s:RecipeSnapshot,row:JsonObject?,close:()->Unit,save:(JsonObject)->Unit) {
    val quantities=LocalQuantityFormatter.current
    var day by remember { mutableStateOf(row?.recipeText("day") ?: java.time.LocalDate.now().toString()) };var type by remember { mutableStateOf(row?.recipeText("type") ?: "recipe") }
    var recipe by remember { mutableStateOf(row?.recipeId("recipe_id")) };var section by remember { mutableStateOf(row?.recipeId("section_id")) }
    var product by remember { mutableStateOf(row?.recipeId("product_id")) };var unit by remember { mutableStateOf(row?.recipeId("product_qu_id")) }
    val amount=remember { RecipeAmountInput(row?.recipeDecimal(if(type=="product")"product_amount" else "recipe_servings") ?: BigDecimal.ONE,Locale.getDefault(),quantities) };var text by remember { mutableStateOf(amount.text) };var note by remember { mutableStateOf(row?.recipeText("note").orEmpty()) }
    val valid=runCatching { java.time.LocalDate.parse(day);amount.saved().signum()>0 }.getOrDefault(false) && section!=null && (type=="note" || (type=="recipe" && recipe!=null) || (type=="product" && product!=null && unit!=null && s.factor(product!!,unit!!)!=null))
    RecipeDialog("Meal plan entry",close,save={save(buildJsonObject {
        put("day",day);put("type",type);put("section_id",section!!);put("note",note)
        if(type=="recipe") { put("recipe_id",recipe!!);put("recipe_servings",number(amount.saved())) }
        if(type=="product") { put("product_id",product!!);put("product_qu_id",unit!!);put("product_amount",number(amount.saved())) }
    })},valid=valid) {
        LabeledTextField(day,{day=it},label = "Day", isError = !runCatching { java.time.LocalDate.parse(day) }.isSuccess, supportingText = "Enter a date as YYYY-MM-DD.")
        if(row==null)Row { listOf("recipe","product","note").forEach { t->FilterChip(type==t,onClick={type=t},label={Text(t)}) } }
        RecipeChoice("Section",s.rows("meal_plan_sections"),section){section=it}
        if(type=="recipe")RecipeChoice("Recipe",s.normal(),recipe){recipe=it}
        if(type=="product") { RecipeChoice("Product",s.rows("products"),product){product=it;unit=null};RecipeChoice("Unit",s.rows("quantity_units").filter { product!=null && it.recipeId("id")?.let { u->s.factor(product!!,u)!=null }==true },unit){unit=it} }
        if(type!="note")LabeledTextField(text,{text=it;amount.change(it)},label = if(type=="recipe")"Servings" else "Amount")
        LabeledTextField(note,{note=it},label = "Notes")
    }
}

@Composable private fun CookingMode(row:JsonObject,s:RecipeSnapshot) {
    val view=LocalView.current
    DisposableEffect(view) { val previous=view.keepScreenOn;view.keepScreenOn=true;onDispose { view.keepScreenOn=previous } }
    val steps=remember(row){recipeSteps(row.recipeText("description"))};var index by rememberSaveable { mutableStateOf(0) }
    var checked by rememberSaveable { mutableStateOf(listOf<Long>()) }
    Text("Ingredient checklist",style=MaterialTheme.typography.titleLarge)
    s.rows("recipes_pos").filter { it.recipeId("recipe_id")==row.recipeId("id") }.forEach { ingredient->val id=ingredient.recipeId("id")!!
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=id in checked,role=Role.Checkbox,onValueChange={done->checked=if(done)checked+id else checked-id}),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { Checkbox(id in checked,null);Text("${s.name("products",ingredient.recipeId("product_id"))} · ${ingredient.recipeText("note")}") }
    }
    if(steps.isEmpty()) Text("No cooking instructions yet.")
    if(steps.isNotEmpty()) {
        index=index.coerceIn(0,steps.lastIndex);Text("Step ${index+1} of ${steps.size}",style=MaterialTheme.typography.titleLarge);Text(steps[index],style=MaterialTheme.typography.headlineSmall)
        Row { SecondaryButton(onClick={index--},enabled=index>0,modifier=Modifier.heightIn(min=56.dp)){Text("Previous")};Spacer(Modifier.width(8.dp));PrimaryButton(onClick={index++},enabled=index<steps.lastIndex,modifier=Modifier.heightIn(min=56.dp)){Text("Next")} }
    }
    var minutes by rememberSaveable { mutableStateOf("5") };var timers by rememberSaveable { mutableStateOf(listOf<Long>()) };var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(timers) { while(timers.isNotEmpty()) { now=android.os.SystemClock.elapsedRealtime();delay(1000) } }
    LabeledTextField(minutes,{minutes=it},label = "Timer minutes", isError = minutes.toLongOrNull()?.let { it in 1..1440 } != true, supportingText = "Enter 1 to 1440 minutes.")
    SecondaryButton(onClick={timers=timers+(android.os.SystemClock.elapsedRealtime()+minutes.toLong()*60000)},enabled=minutes.toLongOrNull()?.let { it in 1..1440 }==true){Text("Start timer for step ${index+1}")}
    timers.forEachIndexed { timer,deadline->Row { val remaining=((deadline-now).coerceAtLeast(0)+999)/1000;Text("Timer ${timer+1}: ${if(remaining==0L)"Done" else "${remaining/60}:${(remaining%60).toString().padStart(2,'0')}"}");QuietButton(onClick={timers=timers.filterIndexed { i,_->i!=timer }}){Text("Dismiss")} } }
}

@Composable internal fun ImportRecipeReview(draft:RecipeImport,s:RecipeSnapshot,busy:Boolean,load:()->Unit,close:()->Unit,save:(JsonObject,List<JsonObject>)->Unit) {
    val quantities=LocalQuantityFormatter.current
    var name by remember(draft){mutableStateOf(draft.name)};var instructions by remember(draft){mutableStateOf(draft.instructions)}
    val amount=remember(draft){draft.servings?.let { RecipeAmountInput(it,Locale.getDefault(),quantities) }};var servings by remember(draft){mutableStateOf(amount?.text.orEmpty())}
    fun currentServings()=amount?.saved() ?: decimal(servings)
    var mapped by remember(draft){mutableStateOf(List(draft.ingredients.size){JsonObject(emptyMap())})}
    var raw by remember(draft){mutableStateOf(draft.ingredients)};var add by remember(draft){mutableStateOf("")}
    val valid=!busy && name.isNotBlank() && runCatching { currentServings().signum()>0 && recipeDescription("",draft.source).isNotBlank() }.getOrDefault(false) && mapped.size==raw.size && mapped.all { r->r.recipeId("product_id")!=null && r.recipeId("qu_id")!=null && r.recipeDecimal("amount")?.signum()==1 }
    RecipeDialog("Review shared recipe",close,save={save(buildJsonObject { put("name",name);put("description",recipeDescription(instructions,draft.source));put("base_servings",number(currentServings())) },mapped.mapIndexed { i,row->JsonObject(row+("note" to JsonPrimitive(raw[i]))) })},valid=valid) {
        Text("Source: ${draft.source}");QuietButton(onClick=load,enabled=!busy){Text("Load recipe details")}
        Text("Map each ingredient to an existing Grocy product and unit. Products are never created here.")
        LabeledTextField(name,{name=it},label = "Recipe name");LabeledTextField(servings,{servings=it;amount?.change(it)},label = "Base servings · required", isError=!runCatching { currentServings().signum()>0 }.getOrDefault(false), supportingText=if(!runCatching { currentServings().signum()>0 }.getOrDefault(false)) "Enter servings greater than zero." else null);LabeledTextField(instructions,{instructions=it},label = "Instructions · one step per line")
        raw.forEachIndexed { i,line->
            Text(line);val m=mapped[i]
            fun update(key:String,value:JsonElement){mapped=mapped.toMutableList().also { it[i]=JsonObject(m+(key to value)) }}
            RecipeChoice("Product",s.rows("products"),m.recipeId("product_id")){p->mapped=mapped.toMutableList().also { it[i]=buildJsonObject { put("product_id",p) } }}
            RecipeChoice("Unit",s.rows("quantity_units").filter { r->m.recipeId("product_id")?.let { p->r.recipeId("id")?.let { u->s.factor(p,u)!=null } }==true },m.recipeId("qu_id")){update("qu_id",JsonPrimitive(it))}
            var amount by remember(draft,line,i){mutableStateOf("")}
            LabeledTextField(amount,{amount=it;update("amount",runCatching { number(decimal(it)) }.getOrDefault(JsonNull))},label = "Reviewed amount")
            QuietButton(onClick={raw=raw.filterIndexed { j,_->j!=i };mapped=mapped.filterIndexed { j,_->j!=i }}){Text("Exclude ingredient")}
        }
        LabeledTextField(add,{add=it},label = "Add an ingredient manually");QuietButton(onClick={raw=raw+add;mapped=mapped+JsonObject(emptyMap());add=""},enabled=add.isNotBlank()){Text("Add for mapping")}
    }
}

@Composable private fun RecipeToggle(label:String,checked:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=checked,role=Role.Checkbox,onValueChange=onChange),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked,null)
        Text(label,Modifier.weight(1f))
    }
}
