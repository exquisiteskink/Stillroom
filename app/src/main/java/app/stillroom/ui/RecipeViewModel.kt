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
    private val ui=AccountBoundState(RecipeUiState());val state=ui.flow
    private var identity:Account?=null;private var work:Job?=null;private var pictures:Job?=null
    init { viewModelScope.launch { accounts.state.collect { next->
        if(identity!=next.active) { work?.cancel();pictures?.cancel();identity=next.active;val url=state.value.sharedUrl;ui.reset(RecipeUiState(sharedUrl=url,imported=url?.let { RecipeImport(it,"","",emptyList(),null) }));if(next.active!=null && RecipeAccess.allowed(next.active.permissions))refresh() }
    } } }
    fun refresh()=execute { it.sync() }
    fun loadPictures() {
        val names=state.value.snapshot.normal().map { it.recipeText("picture_file_name") }.filter { it.isNotBlank() }.distinct()
        if(names.isEmpty() || identity==null)return
        val bound=ui.publisher()
        pictures?.cancel()
        pictures=viewModelScope.launch {
            names.forEach { name->
                if(!bound.current)return@launch
                if(name in state.value.pictures)return@forEach
                try { val bytes=accounts.withRecipes { r->r.image(name) };bound.publish { it.copy(pictures=it.pictures+(name to bytes),image=it.image ?: bytes) } }
                catch(e:CancellationException){throw e}
                catch(_:Exception){}
            }
        }
    }
    fun save(entity:String,id:Long?,fields:JsonObject)=execute { it.save(entity,id,fields);it.sync() }
    fun delete(entity:String,id:Long)=execute { it.delete(entity,id);it.sync() }
    fun review(id:Long)=execute { r->val review=r.review(id);publish { it.copy(review=review) } }
    fun closeReview() { ui.update { it.copy(review=null) } }
    fun consume() { val review=state.value.review ?: return;execute { it.consume(review);it.sync();publish { s->s.copy(review=null) } } }
    fun missing(id:Long,list:Long)=execute { it.missing(id,list);it.sync() }
    fun share(url:String) { if(url.length<=4096)ui.update { it.copy(sharedUrl=url,imported=RecipeImport(url,"","",emptyList(),null)) } }
    fun loadUrl(url:String)=execute { r->val imported=r.importUrl(url);publish { it.copy(imported=imported,sharedUrl=url) } }
    fun closeImport() { ui.update { it.copy(imported=null,sharedUrl=null) } }
    fun import(fields:JsonObject,ingredients:List<JsonObject>)=execute { it.createImport(fields,ingredients);it.sync();publish { s->s.copy(imported=null,sharedUrl=null) } }
    fun loadImage(name:String) {
        val cached=state.value.pictures[name]
        if(cached!=null) { ui.update { it.copy(image=cached) };return }
        execute { r->val bytes=r.image(name);publish { it.copy(image=bytes,pictures=it.pictures+(name to bytes)) } }
    }
    fun clearImage() { ui.update { it.copy(image=null) } }
    fun attachImage(recipe:Long,bytes:ByteArray)=execute {
        val name="stillroom-${java.util.UUID.randomUUID()}.jpg"
        it.image(name,bytes);it.save("recipes",recipe,buildJsonObject { put("picture_file_name",name) });it.sync()
    }
    private fun execute(action:suspend AccountBoundState<RecipeUiState>.Publisher.(ManageRecipes)->Unit) {
        if(state.value.busy || identity==null)return
        val bound=ui.publisher()
        work=viewModelScope.launch {
            bound.publish { it.copy(busy=true,error=null) }
            try { accounts.withRecipes { r->bound.action(r);val operations=r.operations();val snapshot=r.snapshot();bound.publish { it.copy(operations=operations,snapshot=snapshot) } };if(bound.current)loadPictures() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { bound.publish { it.copy(error=e.message ?: "Recipe sync failed.",snapshot=if(e is GrocyFailure && e.status in setOf(401,403))RecipeSnapshot() else it.snapshot) } }
            finally { bound.publish { it.copy(busy=false) } }
        }
    }
}
