package app.stillroom.domain

class GetAppName(private val repository: AppInfoRepository) {
    operator fun invoke(): String = repository.appName()
}
