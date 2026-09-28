package me.nathanfallet.aiplayspokemon.decision.jev

import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal client for Jev (TypeSafe's System One API), built on the JDK HTTP client.
 *
 * Retries rate limits (429) and overload (529) with exponential backoff, as the API docs recommend.
 *
 * The [endpoint] defaults to TypeSafe's cloud, where the real Jev runs (it isn't available for
 * self-hosting). It can point to any server speaking the same `POST /v1/systemone` protocol, e.g. an
 * open-weight Jev reproduction running locally; such servers usually need no [apiKey].
 */
class JevClient(
    private val apiKey: String?,
    val model: String = DEFAULT_MODEL,
    val endpoint: URI = DEFAULT_ENDPOINT,
    private val maxAttempts: Int = 4,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** Sends one request (a state + its questions) and returns the typed answers. */
    suspend fun ask(request: SystemOneRequest): SystemOneResponse {
        val body = json.encodeToString(SystemOneRequest.serializer(), request)
        val httpRequest = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .apply { if (apiKey != null) header("Authorization", "Bearer $apiKey") }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        var backoffMillis = 500L
        repeat(maxAttempts) { attempt ->
            val response = http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()).await()
            when (response.statusCode()) {
                200 -> return json.decodeFromString(SystemOneResponse.serializer(), response.body())
                429, 529 -> if (attempt < maxAttempts - 1) {
                    delay(backoffMillis)
                    backoffMillis *= 2
                }

                else -> throw JevException(response.statusCode(), response.body())
            }
        }
        throw JevException(429, "Still rate limited after $maxAttempts attempts")
    }

    companion object {
        /** Alias of TypeSafe's flagship model. */
        const val DEFAULT_MODEL = "jev-latest"

        /** TypeSafe's hosted API. */
        val DEFAULT_ENDPOINT: URI = URI.create("https://api.typesafe.ai/v1/systemone")

        val json = Json {
            ignoreUnknownKeys = true // be tolerant to new response fields
            explicitNulls = false
            classDiscriminator = "type"
        }
    }
}

class JevException(val status: Int, body: String) : IOException("Jev API error $status: $body")
