/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.telemetry.glean.net

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.annotation.VisibleForTesting.PRIVATE
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import mozilla.components.concept.fetch.isClientError
import mozilla.components.concept.fetch.isSuccess
import mozilla.telemetry.glean.BuildConfig
import mozilla.telemetry.glean.config.Configuration
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A simple ping Uploader, which implements a "send once" policy, never
 * storing or attempting to send the ping again.
 */
internal open class HttpPingUploader : PingUploader {
    companion object {
        val LOG_TAG: String = "glean/HttpPingUploader"
    }

    /**
     * Log the contents of a ping to the console, if configured to do so in
     * [Configuration.logPings].
     *
     * @param path the URL path to append to the server address
     * @param data the serialized text data to send
     * @param config the Glean configuration object
     */
    private fun logPing(path: String, data: String, config: Configuration) {
        if (config.logPings) {
            // Parse and reserialize the JSON so it has indentation and is human-readable.
            try {
                val json = JSONObject(data)
                val indented = json.toString(2)

                Log.d(LOG_TAG, "Glean ping to URL: $path\n$indented")
            } catch (e: JSONException) {
                Log.d(LOG_TAG, "Exception parsing ping as JSON: $e") // $COVERAGE-IGNORE$
            }
        }
    }

    /**
     * Synchronously upload a ping to Mozilla servers.
     * Note that the `X-Client-Type`: `Glean` and `X-Client-Version`: <SDK version>
     * headers are added to the HTTP request in addition to the UserAgent. This allows
     * us to easily handle pings coming from Glean on the legacy Mozilla pipeline.
     *
     * @param path the URL path to append to the server address
     * @param data the serialized text data to send
     * @param config the Glean configuration object
     *
     * @return true if the ping was correctly dealt with (sent successfully
     *         or faced an unrecoverable error), false if there was a recoverable
     *         error callers can deal with.
     */
    override fun upload(path: String, data: String, config: Configuration): Boolean {
        logPing(path, data, config)

        val request = buildRequest(path, data, config)

        return try {
            performUpload(config.httpClient.value, request)
        } catch (e: IOException) {
            Log.w(LOG_TAG, "IOException while uploading ping", e)
            false
        }
    }

    @VisibleForTesting(otherwise = PRIVATE)
    internal fun buildRequest(path: String, data: String, config: Configuration): Request {
        val headers = MutableHeaders(
            "Content-Type" to "application/json; charset=utf-8",
            "User-Agent" to config.userAgent,
            "Date" to createDateHeaderValue(),
            // Add headers for supporting the legacy pipeline.
            "X-Client-Type" to "Glean",
            "X-Client-Version" to BuildConfig.LIBRARY_VERSION
        )

        var endpoint = config.serverEndpoint

        // If there is a pingTag set, then this header needs to be added in order to flag pings
        // for "debug view" use.
        config.pingTag?.let {
            headers.append("X-Debug-ID", it)

            // NOTE: Tagged pings must be redirected to the GCP endpoint as the AWS endpoint isn't
            // configured to handle them.  This may pose an issue with testing if a local server
            // is used to capture pings.
            endpoint = Configuration.DEFAULT_DEBUGVIEW_ENDPOINT
        }

        return Request(
            url = endpoint + path,
            method = Request.Method.POST,
            connectTimeout = Pair(config.connectionTimeout, TimeUnit.MILLISECONDS),
            readTimeout = Pair(config.readTimeout, TimeUnit.MILLISECONDS),
            headers = headers,
            // Make sure we are not sending cookies. Unfortunately, HttpURLConnection doesn't
            // offer a better API to do that, so we nuke all cookies going to our telemetry
            // endpoint.
            cookiePolicy = Request.CookiePolicy.OMIT,
            body = Request.Body.fromString(data)
        )
    }

    @Throws(IOException::class)
    internal fun performUpload(client: Client, request: Request): Boolean {
        Log.d(LOG_TAG, "Submitting ping to: ${request.url}")
        client.fetch(request).use { response ->
            when {
                response.isSuccess -> {
                    // Known success errors (2xx):
                    // 200 - OK. Request accepted into the pipeline.

                    // We treat all success codes as successful upload even though we only expect 200.
                    Log.d(LOG_TAG, "Ping successfully sent (${response.status})")
                    return true
                }

                response.isClientError -> {
                    // Known client (4xx) errors:
                    // 404 - not found - POST/PUT to an unknown namespace
                    // 405 - wrong request type (anything other than POST/PUT)
                    // 411 - missing content-length header
                    // 413 - request body too large (Note that if we have badly-behaved clients that
                    //       retry on 4XX, we should send back 202 on body/path too long).
                    // 414 - request path too long (See above)

                    // Something our client did is not correct. It's unlikely that the client is going
                    // to recover from this by re-trying again, so we just log and error and report a
                    // successful upload to the service.
                    Log.e(LOG_TAG, "Server returned client error code ${response.status} for ${request.url}")
                    return true
                }

                else -> {
                    // Known other errors:
                    // 500 - internal error

                    // For all other errors we log a warning an try again at a later time.
                    Log.w(LOG_TAG, "Server returned response code ${response.status} for ${request.url}")
                    return false
                }
            }
        }
    }
}
