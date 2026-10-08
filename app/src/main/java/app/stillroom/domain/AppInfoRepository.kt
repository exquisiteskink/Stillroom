package app.stillroom.domain

/** Local application identity, independent of the connected Grocy account. */
fun interface AppInfoRepository {
    fun appName(): String
}
