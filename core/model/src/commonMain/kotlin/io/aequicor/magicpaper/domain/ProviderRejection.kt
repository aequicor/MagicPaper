package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

/**
 * Что именно провайдер отверг. Признак выбирает действие человека, поэтому он свой, а не
 * дословный текст провайдера: тело ответа может содержать фрагмент запроса и не должно
 * попадать ни в журнал, ни в интерфейс.
 *
 * [ENTITLEMENT] — у ключа нет права на эту модель: закончился баланс либо модель не покрыта
 * пакетом ресурсов или подпиской. Признак отделяет отказ в доступе от ограничения числа
 * запросов: провайдеры сообщают о нём тем же статусом 429, но повтор здесь ничего не меняет.
 */
enum class ProviderRefusal { PARAMETER, CONTEXT_LENGTH, MODEL, TOOLS, ENTITLEMENT, OTHER,
    /** The account a subscription connection answers on is signed out; signing in, not a key, resolves it. */
    SIGN_IN,
}

/**
 * Машинные поля отказа провайдера: код, тип ошибки, имя отвергнутого параметра и выведенный
 * из формы отказа признак [ProviderRefusal].
 *
 * Провайдер формирует токены сам (`unsupported_parameter` / `reasoning_effort`,
 * `InvalidParameter`, `Throttling.RateQuota`), поэтому их можно писать в журнал и показывать
 * человеку. Свободного текста здесь нет намеренно.
 */
@Serializable
data class ProviderRejection(
    val code: String? = null,
    val type: String? = null,
    val param: String? = null,
    val refusal: ProviderRefusal = ProviderRefusal.OTHER,
)

/**
 * Безопасные поля журнала для отказа провайдера: статус, код, признак и имя параметра.
 * Тела ответа и сообщения исключения здесь нет — только токены провайдера и наш признак.
 */
fun LlmTransportException.logFields(): Map<String, String> = buildMap {
    put("status", statusCode.toString())
    rejection?.code?.let { put("code", it) }
    rejection?.param?.let { put("param", it) }
    rejection?.let { put("refusal", it.refusal.name.lowercase()) }
}

/**
 * Причина отказа словами приложения: у человека есть действие, а не тело ответа провайдера.
 * Порядок — от частного к общему; имя параметра важнее кода, потому что именно его нужно
 * убрать или поменять в настройках модели.
 */
fun LlmTransportException.safeReason(): String = when {
    rejection?.refusal == ProviderRefusal.SIGN_IN -> "Подписка не подключена: войдите в аккаунт в настройках движков."
    // Отказ в доступе важнее статуса: тот же 429 провайдер использует и для «повторите позже»,
    // и для «ключ не оплачивает эту модель», а действие человека у них противоположное.
    rejection?.refusal == ProviderRefusal.ENTITLEMENT ->
        "Провайдер не дал ключу доступа к модели: нет баланса или пакета ресурсов, который её покрывает. " +
            "Проверьте адрес подключения и модель: ключ подписки принимает только свой адрес."
    statusCode == 401 || statusCode == 403 -> "Провайдер не принял ключ подключения. Проверьте его в настройках."
    statusCode == 429 -> "Провайдер ограничил число запросов. Повторите позже."
    rejection?.refusal == ProviderRefusal.CONTEXT_LENGTH ->
        "Запрос не помещается в контекст модели. Уберите часть источников или выберите модель с большим контекстом."
    rejection?.refusal == ProviderRefusal.MODEL || statusCode == 404 ->
        "Провайдер не знает выбранную модель или адрес запроса. Проверьте подключение."
    rejection?.refusal == ProviderRefusal.TOOLS ->
        "Модель не принимает инструменты в этом подключении. Выберите модель с поддержкой инструментов."
    rejection?.param != null ->
        "Провайдер отклонил параметр запроса «${rejection.param}». Проверьте параметры модели в настройках."
    rejection?.code != null -> "Провайдер отклонил запрос: ${rejection.code}."
    statusCode in 400..499 -> "Провайдер отклонил запрос. Проверьте параметры модели."
    else -> "Провайдер не подтвердил ответ."
}
