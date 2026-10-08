package app.stillroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stillroom.data.*
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class RecipeUiState(val snapshot:RecipeSnapshot=RecipeSnapshot(),val operations:List<PendingChange> = emptyList(),val busy:Boolean=false,val error:String?=null,
    val review:RecipeConsumeReview?=null,val imported:RecipeImport?=null,val sharedUrl:String?=null,val image:ByteArray?=null,val pictures:Map<String,ByteArray> = emptyMap())
class RecipeViewModel(private val accounts:AndroidAccountsRepository):ViewModel() {
    private val mutable=MutableStateFlow(RecipeUiState());val state=mutable.asStateFlow()
    private var identity:Account?=null;private var work:Job?=null;private var pictures:Job?=null;private var generation=0L
    init { viewModelScope.launch { accounts.state.collect { next->
        if(identity!=next.active) { generation++;work?.cancel();pictures?.cancel();identity=next.active;val url=state.value.sharedUrl;mutable.value=RecipeUiState(sharedUrl=url,imported=url?.let { RecipeImport(it,"","",emptyList(),null) });if(next.active!=null && RecipeAccess.allowed(next.active.permissions))refresh() }
    } } }
    fun refresh()=execute { it.sync() }
    fun loadPictures() {
        val names=state.value.snapshot.normal().map { it.recipeText("picture_file_name") }.filter { it.isNotBlank() }.distinct()
        if(names.isEmpty() || identity==null)return
        val bound=generation
        pictures?.cancel()
        pictures=viewModelScope.launch {
            names.forEach { name->
                if(bound!=generation)return@launch
                if(name in state.value.pictures)return@forEach
                try { accounts.withRecipes { r->val bytes=r.image(name);if(bound==generation)mutable.value=state.value.copy(pictures=state.value.pictures+(name to bytes),image=if(state.value.image==null)bytes else state.value.image) } }
                catch(e:CancellationException){throw e}
                catch(_:Exception){}
            }
        }
    }
    fun save(entity:String,id:Long?,fields:JsonObject)=execute { it.save(entity,id,fields);it.sync() }
    fun delete(entity:String,id:Long)=execute { it.delete(entity,id);it.sync() }
    fun review(id:Long)=execute { mutable.value=state.value.copy(review=it.review(id)) }
    fun closeReview() { mutable.value=state.value.copy(review=null) }
    fun consume() { val review=state.value.review ?: return;execute { it.consume(review);it.sync();mutable.value=state.value.copy(review=null) } }
    fun missing(id:Long,list:Long)=execute { it.missing(id,list);it.sync() }
    fun share(url:String) { if(url.length<=4096)mutable.value=state.value.copy(sharedUrl=url,imported=RecipeImport(url,"","",emptyList(),null)) }
    fun loadUrl(url:String)=execute { mutable.value=state.value.copy(imported=it.importUrl(url),sharedUrl=url) }
    fun closeImport() { mutable.value=state.value.copy(imported=null,sharedUrl=null) }
    fun import(fields:JsonObject,ingredients:List<JsonObject>)=execute { it.createImport(fields,ingredients);it.sync();closeImport() }
    fun loadImage(name:String) {
        val cached=state.value.pictures[name]
        if(cached!=null) { mutable.value=state.value.copy(image=cached);return }
        execute { val bytes=it.image(name);mutable.value=state.value.copy(image=bytes,pictures=state.value.pictures+(name to bytes)) }
    }
    fun clearImage() { mutable.value=state.value.copy(image=null) }
    fun attachImage(recipe:Long,bytes:ByteArray)=execute {
        val name="stillroom-${java.util.UUID.randomUUID()}.jpg"
        it.image(name,bytes);it.save("recipes",recipe,buildJsonObject { put("picture_file_name",name) });it.sync()
    }
    private fun execute(action:suspend(ManageRecipes)->Unit) {
        if(state.value.busy || identity==null)return
        val bound=generation
        work=viewModelScope.launch {
            mutable.value=state.value.copy(busy=true,error=null)
            try { accounts.withRecipes { r->action(r);mutable.value=state.value.copy(operations=r.operations(),snapshot=r.snapshot()) };if(bound==generation)loadPictures() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { mutable.value=state.value.copy(error=e.message ?: "Recipe sync failed.",snapshot=if(e is GrocyFailure && e.status in setOf(401,403))RecipeSnapshot() else state.value.snapshot) }
            finally { if(bound==generation)mutable.value=state.value.copy(busy=false) }
        }
    }
}
