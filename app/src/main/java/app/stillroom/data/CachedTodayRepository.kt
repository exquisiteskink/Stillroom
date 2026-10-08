package app.stillroom.data

import app.stillroom.domain.*
import java.time.LocalDate

class CachedTodayRepository(private val account:Account,private val db:AccountDatabase) {
    fun snapshot(day:LocalDate=LocalDate.now()):TodaySnapshot {
        if(db.get("background","access-denied")=="true")return TodaySnapshot(account=account,missing=CachedToday.paths(account),accessDenied=true)
        val data=CachedToday.paths(account).mapNotNull { path->db.get(path,"current")?.let { path to it } }.toMap()
        return CachedToday.build(account,data,day)
    }
    suspend fun refresh(cache:CachedGrocyRepository) {
        for(path in CachedToday.paths(account))cache.read(path)
    }
}
