package org.wikipedia.analytics.eventplatform

import android.widget.Toast
import androidx.core.os.postDelayed
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.wikipedia.BuildConfig
import org.wikipedia.WikipediaApp
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.dataclient.okhttp.HttpStatusException
import org.wikipedia.settings.Prefs
import org.wikipedia.util.ReleaseUtil
import org.wikipedia.util.log.L
import java.net.HttpURLConnection
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object EventPlatformClient {
    /**
     * Stream configs to be fetched on startup and stored for the duration of the app lifecycle.
     */
    private val STREAM_CONFIGS = ConcurrentHashMap<String, StreamConfig>()

    /**
     * List of events that will be queued before the first time that stream configs are fetched.
     */
    private val INITIAL_QUEUE = mutableListOf<Event>()

    /*
     * When ENABLED is false, items can be enqueued but not dequeued.
     * Timers will not be set for enqueued items.
     * QUEUE will not grow beyond MAX_QUEUE_SIZE.
     *
     * Inputs: network connection state on/off, connection state bad y/n?
     * Taken out of iOS client, but flag can be set on the request object to wait until connected to send
     */
    private var ENABLED = WikipediaApp.instance.isOnline

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    fun setStreamConfig(streamConfig: StreamConfig) {
        STREAM_CONFIGS[streamConfig.streamName] = streamConfig
    }

    fun getStreamConfig(name: String): StreamConfig? {
        return STREAM_CONFIGS[name]
    }

    /**
     * Set whether the client is enabled. This can react to device online/offline state as well
     * as other considerations.
     */
    fun setEnabled(enabled: Boolean) {
        ENABLED = enabled
        if (ENABLED) {
            /*
             * Try immediately to send any enqueued items. Otherwise another
             * item must be enqueued before sending is triggered.
             */
            OutputBuffer.sendAllScheduled()
        }
    }

    /**
     * Submit an event to be enqueued and sent to the Event Platform
     *
     * @param event event
     */
    fun submit(event: Event) {
        if (STREAM_CONFIGS.isEmpty()) {
            // We haven't gotten stream configs yet, so queue up the event in our initial queue.
            synchronized(INITIAL_QUEUE) {
                // We want the queue to work as a circular buffer, so if it's full, remove the oldest item.
                if (INITIAL_QUEUE.size >= Prefs.analyticsQueueSize) {
                    INITIAL_QUEUE.removeAt(0)
                }
                INITIAL_QUEUE.add(event)
            }
            return
        }
        doSubmit(event)
    }

    private fun doSubmit(event: Event) {
        if (!SamplingController.isInSample(event)) {
            return
        }
        OutputBuffer.schedule(event)
    }

    fun flushCachedEvents() {
        if (STREAM_CONFIGS.isNotEmpty()) {
            // If we have events left over in our initial queue, submit them now, so they'll be send in this pass.
            synchronized(INITIAL_QUEUE) {
                if (INITIAL_QUEUE.isNotEmpty()) {
                    INITIAL_QUEUE.forEach {
                        doSubmit(it)
                    }
                    INITIAL_QUEUE.clear()
                }
            }
        }
        OutputBuffer.sendAllScheduled()
    }

    suspend fun refreshStreamConfigs() {
        val response = ServiceFactory.get(WikiSite(BuildConfig.META_WIKI_BASE_URI)).getStreamConfigs()
        updateStreamConfigs(response.streamConfigs)
    }

    private fun updateStreamConfigs(streamConfigs: Map<String, StreamConfig>) {
        STREAM_CONFIGS.clear()
        STREAM_CONFIGS.putAll(streamConfigs)
        Prefs.streamConfigs = STREAM_CONFIGS
    }

    fun setUpStreamConfigs() {
        STREAM_CONFIGS.clear()
        STREAM_CONFIGS.putAll(Prefs.streamConfigs)
        MainScope().launch(CoroutineExceptionHandler { _, t ->
            L.e(t)
        }) {
            refreshStreamConfigs()
        }
    }

    /**
     * OutputBuffer: buffers events in a queue prior to transmission
     *
     * Transmissions are not sent at a uniform offset but are shaped into
     * 'bursts' using a combination of queue size and debounce time.
     *
     * These concentrate requests (and hence, theoretically, radio awake state)
     * so as not to contribute to battery drain.
     */
    internal object OutputBuffer {
        private val QUEUE = mutableListOf<Event>()

        /*
         * When an item is added to QUEUE, wait this many ms before sending.
         * If another item is added to QUEUE during this time, reset the countdown.
         */
        private const val WAIT_MS = 30000L
        private const val TOKEN = "sendScheduled"
        private val MAX_QUEUE_SIZE get() = Prefs.analyticsQueueSize

        fun sendAllScheduled() {
            WikipediaApp.instance.mainThreadHandler.removeCallbacksAndMessages(TOKEN)
            if (ENABLED) {
                val eventsByStream: Map<String, List<Event>>
                synchronized(QUEUE) {
                    eventsByStream = QUEUE.groupBy { it.stream }
                    QUEUE.clear()
                }
                send(eventsByStream)
            }
        }

        /**
         * Schedule a request to be sent.
         *
         * @param event event data
         */
        fun schedule(event: Event) {
            if (ENABLED || QUEUE.size <= MAX_QUEUE_SIZE) {
                synchronized(QUEUE) {
                    QUEUE.add(event)
                }
            }
            if (ENABLED) {
                if (QUEUE.size >= MAX_QUEUE_SIZE) {
                    sendAllScheduled()
                } else {
                    // The arrival of a new item interrupts the timer and resets the countdown.
                    WikipediaApp.instance.mainThreadHandler.removeCallbacksAndMessages(TOKEN)
                    WikipediaApp.instance.mainThreadHandler.postDelayed(WAIT_MS, TOKEN) {
                        sendAllScheduled()
                    }
                }
            }
        }

        /**
         * If sending is enabled, attempt to send the provided events.
         * Also batch the events ordered by their streams, as the QUEUE
         * can contain events of different streams
         */
        private fun send(eventsByStream: Map<String, List<Event>>) {
            eventsByStream.forEach { (stream, events) ->
                getStreamConfig(stream)?.let {
                    sendEventsForStream(it, events)
                }
            }
        }

        private fun sendEventsForStream(streamConfig: StreamConfig, events: List<Event>) {
            coroutineScope.launch(CoroutineExceptionHandler { _, caught ->
                L.e(caught)
                if (caught is HttpStatusException) {
                    if (caught.code >= HttpURLConnection.HTTP_INTERNAL_ERROR) {
                        // TODO: For errors >= 500, queue up to retry?
                    } else {
                        // Something unexpected happened.
                        if (ReleaseUtil.isDevRelease) {
                            // If it's a pre-beta release, show a loud toast to signal that
                            // a potential issue should be investigated.
                            WikipediaApp.instance.mainThreadHandler.post {
                                Toast.makeText(WikipediaApp.instance, caught.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }) {
                val eventService = if (ReleaseUtil.isDevRelease) ServiceFactory.getAnalyticsRest(streamConfig).postEvents(events) else
                    ServiceFactory.getAnalyticsRest(streamConfig).postEventsHasty(events)
                when (eventService.code()) {
                    HttpURLConnection.HTTP_CREATED,
                    HttpURLConnection.HTTP_ACCEPTED -> {}
                    else -> {
                        // Received successful response, but unexpected HTTP code.
                        // TODO: queue up to retry?
                    }
                }
            }
        }
    }

    /**
     * AssociationController: provides associative identifiers and manage their
     * persistence
     *
     * Identifiers correspond to various scopes e.g. 'pageview', 'session', and 'device'.
     *
     * TODO: Possibly get rid of the pageview type?  Does it make sense on apps?  It is not in the iOS library currently.
     * On apps, a "session" starts when the app is loaded, and ends when completely closed, or after 15 minutes of inactivity
     * Save a ts when going into bg, then when returning to foreground, & if it's been more than 15 mins, start a new session, else continue session from before
     * Possible to query/track time since last interaction? (For future)
     */
    internal object AssociationController {
        private var PAGEVIEW_ID: String? = null
        private var SESSION_ID: String? = null

        /**
         * Generate a pageview identifier.
         *
         * @return pageview ID
         *
         * The identifier is a string of 20 zero-padded hexadecimal digits
         * representing a uniformly random 80-bit integer.
         */
        val pageViewId: String
            get() {
                if (PAGEVIEW_ID == null) {
                    PAGEVIEW_ID = generateRandomId()
                }
                return PAGEVIEW_ID!!
            }

        /**
         * Generate a session identifier.
         *
         * @return session ID
         *
         * The identifier is a string of 20 zero-padded hexadecimal digits
         * representing a uniformly random 80-bit integer.
         */
        val sessionId: String
            get() {
                if (SESSION_ID == null) {
                    // If there is no runtime value for SESSION_ID, try to load a
                    // value from persistent store.
                    SESSION_ID = Prefs.eventPlatformSessionId
                    if (SESSION_ID == null) {
                        // If there is no value in the persistent store, generate a new value for
                        // SESSION_ID, and write the update to the persistent store.
                        SESSION_ID = generateRandomId()
                        Prefs.eventPlatformSessionId = SESSION_ID
                    }
                }
                return SESSION_ID!!
            }

        fun beginNewSession() {
            // Clear runtime and persisted value for SESSION_ID.
            SESSION_ID = null
            Prefs.eventPlatformSessionId = null

            // A session refresh implies a pageview refresh, so clear runtime value of PAGEVIEW_ID.
            beginNewPageView()
        }

        fun beginNewPageView() {
            PAGEVIEW_ID = null
        }

        /**
         * @return a string of 20 zero-padded hexadecimal digits representing a uniformly random
         * 80-bit integer
         */
        private fun generateRandomId(): String {
            val random = Random()
            return String.format("%08x", random.nextInt()) + String.format("%08x", random.nextInt()) + String.format("%04x", random.nextInt() and 0xFFFF)
        }
    }

    /**
     * SamplingController: computes various sampling functions on the client
     *
     * Sampling is based on associative identifiers, each of which have a
     * well-defined scope, and sampling config, which each stream provides as
     * part of its configuration.
     */
    internal object SamplingController {
        private var SAMPLING_CACHE = mutableMapOf<String, Boolean>()

        /**
         * @param event event
         * @return true if in sample or false otherwise
         */
        fun isInSample(event: Event): Boolean {
            val stream = event.stream
            if (SAMPLING_CACHE.containsKey(stream)) {
                return SAMPLING_CACHE[stream]!!
            }
            val streamConfig = getStreamConfig(stream) ?: return false
            val samplingConfig = streamConfig.samplingConfig
            if (samplingConfig == null || samplingConfig.rate == 1.0) {
                return true
            }
            if (samplingConfig.rate == 0.0) {
                return false
            }
            val inSample = getSamplingValue(samplingConfig.unit) < samplingConfig.rate
            SAMPLING_CACHE[stream] = inSample
            return inSample
        }

        /**
         * @param unit Unit type from sampling config
         * @return a floating point value between 0.0 and 1.0 (inclusive)
         */
        fun getSamplingValue(unit: String): Double {
            val token = getSamplingId(unit).substring(0, 8)
            return token.toLong(16).toDouble() / 0xFFFFFFFFL.toDouble()
        }

        fun getSamplingId(unit: String): String {
            if (unit == SamplingConfig.UNIT_SESSION) {
                return AssociationController.sessionId
            }
            if (unit == SamplingConfig.UNIT_PAGEVIEW) {
                return AssociationController.pageViewId
            }
            if (unit == SamplingConfig.UNIT_DEVICE) {
                return WikipediaApp.instance.appInstallID
            }
            L.e("Bad identifier type")
            return UUID.randomUUID().toString()
        }
    }
}
