package app.stillroom.data

import android.content.Context
import app.stillroom.domain.StockDetailSetting
import app.stillroom.domain.StockDetailsCodec
import app.stillroom.domain.StockDetailsStore

/** Settings → Stock → Shown details, one entry per Grocy account id. Device-local UI preference only; no secrets. */
class AndroidStockDetailsStore(context: Context) : StockDetailsStore {
    private val storage = context.applicationContext.getSharedPreferences("stock_details", Context.MODE_PRIVATE)
    private fun key(accountId: String) = "account_$accountId"
    override fun load(accountId: String) = StockDetailsCodec.decode(storage.getString(key(accountId), null))
    override fun save(accountId: String, settings: List<StockDetailSetting>) { storage.edit().putString(key(accountId), StockDetailsCodec.encode(settings)).apply() }
    override fun clear(accountId: String) { storage.edit().remove(key(accountId)).apply() }
}
