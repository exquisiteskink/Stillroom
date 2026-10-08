package app.stillroom.data

import app.stillroom.domain.AppInfoRepository

class LocalAppInfoRepository : AppInfoRepository {
    override fun appName(): String = "Stillroom"
}
