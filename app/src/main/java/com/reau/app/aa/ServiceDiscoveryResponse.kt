// Rewritten for REau after OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/ServiceDiscoveryResponse.kt,
// commit 0abbe2a (itself adapted from headunit-revived, AGPLv3: aap/protocol/messages/ServiceDiscoveryResponse.kt).
// Kept from it: which protobuf messages make up each service, and the two findings in its comments (Android Auto drops
// a head unit that advertises no audio sink; a microphone is required for the connection).
// Changes: the video geometry comes from an AaConfig instead of OpenCfMoto's bike profiles; the touchscreen is never
// advertised; the key list is this app's; the identity strings are this app's.
package com.reau.app.aa

import com.google.protobuf.Message
import com.google.protobuf.UnknownFieldSet
import com.reau.app.aa.proto.Common
import com.reau.app.aa.proto.Control
import com.reau.app.aa.proto.Media
import com.reau.app.aa.proto.Sensors

/**
 * What this head unit tells Android Auto it has. It decides
 * how Android Auto behaves for the whole session:
 *
 * - **Sensors**: driving status ("unrestricted" keeps the full screen) and night (switches the map's theme).
 * - **Video**: H.264 Baseline at 30 frames a second, 800×480 ([AaVideo]), with the density and margins of [AaConfig].
 * - **Input**: keys and the rotary wheel, and NO touchscreen: the dash has none, and advertising the wheel is what
 *   makes Android Auto draw a focus that keys can move.
 * - **System audio sink**: Android Auto drops a head unit that advertises no audio sink right after discovery. The
 *   sound it sends there is acknowledged and thrown away.
 * - **Microphone**: required for the connection. Advertised, never opened (AapControlMedia answers a request).
 *
 * Not advertised, on purpose: the media and speech audio sinks. Advertising those is what takes the navigation
 * voice off the phone's own output and sends it down this protocol; without them Waze keeps talking through the
 * phone, to the rider's headset.
 */
class ServiceDiscoveryResponse(config: AaConfig) :
    AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE, makeProto(config)) {

    companion object {
        /** KEYCODE values of android.view.KeyEvent, and the rotary wheel's own code. */
        const val KEY_SCROLL_WHEEL = 65536
        const val KEY_UP = 19
        const val KEY_DOWN = 20
        const val KEY_LEFT = 21
        const val KEY_RIGHT = 22
        const val KEY_ENTER = 23
        const val KEY_MEDIA_NEXT = 87
        const val KEY_MEDIA_PREVIOUS = 88
        const val KEY_MEDIA_PLAY_PAUSE = 85
        const val KEY_BACK = 4
        const val KEY_HOME = 3
        const val KEY_ASSISTANT = 84
        const val KEY_CALL = 5
        const val KEY_ENDCALL = 6

        /** The head unit's own NAVIGATION button of Android Auto (0x10002, not an Android keycode): it opens the last navigation app used, full screen, from any screen. */
        const val KEY_NAVIGATION = 65538

        /** The head unit's own MEDIA button of Android Auto (0x10001): it opens the last media app used (proved on the DHU, 17/11/2026: Spotify). */
        const val KEY_MEDIA = 65537

        /** The head unit's own PHONE button of Android Auto (0x10004): it opens the phone. */
        const val KEY_TEL = 65540

        /** The keys declared to Android Auto: the wheel, the four arrows, confirm, back, and (for the dash's
         *  joystick) home and assistant, as OpenCfMoto declares them: without home the joystick cannot reach the app list.
         *  Next and previous song and play/pause, for the actions of the joystick's page. The car's phone keys, call
         *  and end call, for the joystick's answer and end (AaCallKeys). The navigation button, for the joystick's "open the navigation". The media and
         *  phone buttons, for the joystick's "open the music app" and "open the phone". */
        val SUPPORTED_KEYCODES = listOf(KEY_SCROLL_WHEEL, KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, KEY_ENTER, KEY_BACK, KEY_HOME, KEY_ASSISTANT,
            KEY_MEDIA_NEXT, KEY_MEDIA_PREVIOUS, KEY_MEDIA_PLAY_PAUSE, KEY_CALL, KEY_ENDCALL, KEY_NAVIGATION, KEY_MEDIA, KEY_TEL)

        private const val MAKE = "REau"
        private const val MODEL = "REau"
        private const val YEAR = "2026"
        private const val VEHICLE_ID = "reau"
        private const val SOFTWARE_BUILD = "1"

        /**
         * The experiment "Android Auto in motorcycle mode" (debug builds, [AaConfig.motorcycle]): a vehicle id of its own, because Android Auto remembers the
         * vehicle type by the head unit's identity, so the new type only counts under an id it has not seen. Told in the top-level vehicle_id (5) too.
         */
        const val VEHICLE_ID_MOTORCYCLE = "reau-moto-1"

        /** Android Auto's vehicle type for a motorcycle: 0 not said, 1 car, 2 truck, 3 motorcycle (field 9 of the head unit info as Android Auto numbers it). */
        const val VEHICLE_TYPE_MOTORCYCLE = 3L

        /** The vehicle id this configuration announces (the top level's and, in the head unit info, Android Auto's own field 4). */
        fun vehicleIdOf(config: AaConfig): String = if (config.motorcycle) VEHICLE_ID_MOTORCYCLE else VEHICLE_ID

        /** The app's own version (28/10/2026: it was a fixed "0.1.0"), so what Android Auto logs about this head unit says which REau it was. */
        private val SOFTWARE_VERSION = com.reau.app.BuildConfig.VERSION_NAME

        private fun makeProto(config: AaConfig): Message = Control.ServiceDiscoveryResponse.newBuilder().apply {
            make = MAKE
            model = MODEL
            year = YEAR
            vehicleId = vehicleIdOf(config)
            headUnitMake = MAKE
            headUnitModel = MODEL
            headUnitSoftwareBuild = SOFTWARE_BUILD
            headUnitSoftwareVersion = SOFTWARE_VERSION
            driverPosition = Control.DriverPosition.DRIVER_POSITION_LEFT
            canPlayNativeMediaDuringVr = false
            hideProjectedClock = false
            displayName = MAKE
            headunitInfo = if (config.motorcycle) motorcycleHeadUnitInfo() else carHeadUnitInfo()
            addServices(sensors())
            addServices(video(config))
            addServices(input(config))
            addServices(AudioServices.systemAudio())
            if (config.audio) {
                addServices(AudioServices.mediaAudio())
                addServices(AudioServices.guidanceAudio())
                addServices(AudioServices.speechAudio())
            }
            addServices(AudioServices.microphone())
            if (config.navigation) addServices(navigationStatus())
            if (config.media) addServices(mediaPlaybackStatus)
            if (config.phone) addServices(phoneStatus)
        }.build()

        /** The head unit info as it has always gone out, with this app's own field numbers ([Common.HeadUnitInfo]); off, the bytes are these. */
        private fun carHeadUnitInfo(): Common.HeadUnitInfo = Common.HeadUnitInfo.newBuilder().apply {
            headUnitMake = MAKE
            headUnitModel = MODEL
            make = MAKE
            model = MODEL
            year = YEAR
            vehicleId = VEHICLE_ID
            headUnitSoftwareBuild = SOFTWARE_BUILD
            headUnitSoftwareVersion = SOFTWARE_VERSION
        }.build()

        /**
         * The head unit info of the motorcycle experiment, in the numbering of Android Auto's own copy of the message, which is the only reader (this
         * app's [Common.HeadUnitInfo] numbers the same eight strings in another order and has no vehicle type; the generated classes are not touched).
         * So each Kotlin field below is chosen by the NUMBER it puts on the wire, not by its name:
         *
         * | Kotlin field here          | on the wire | what Android Auto reads there |
         * |----------------------------|-------------|-------------------------------|
         * | headUnitMake               | 1           | make                          |
         * | headUnitModel              | 2           | model                         |
         * | make                       | 3           | year                          |
         * | model                      | 4           | vehicle_id                    |
         * | year                       | 5           | head_unit_make                |
         * | headUnitSoftwareBuild      | 6           | head_unit_model               |
         * | vehicleId                  | 7           | head_unit_software_build      |
         * | headUnitSoftwareVersion    | 8           | head_unit_software_version    |
         * | (unknown field, added)     | 9           | vehicle_type (3 = motorcycle) |
         *
         * Field 9 is written as an unknown field: the generated message does not know it, and a protobuf message serializes its unknown fields.
         */
        private fun motorcycleHeadUnitInfo(): Common.HeadUnitInfo = Common.HeadUnitInfo.newBuilder().apply {
            headUnitMake = MAKE // 1: make
            headUnitModel = MODEL // 2: model
            make = YEAR // 3: year
            model = VEHICLE_ID_MOTORCYCLE // 4: vehicle_id
            year = MAKE // 5: head_unit_make
            headUnitSoftwareBuild = MODEL // 6: head_unit_model
            vehicleId = SOFTWARE_BUILD // 7: head_unit_software_build
            headUnitSoftwareVersion = SOFTWARE_VERSION // 8: head_unit_software_version
            setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(VEHICLE_TYPE_FIELD, UnknownFieldSet.Field.newBuilder().addVarint(VEHICLE_TYPE_MOTORCYCLE).build())
                    .build(),
            ) // 9: vehicle_type
        }.build()

        private const val VEHICLE_TYPE_FIELD = 9

        /**
         * Behind a switch: the music status channel, as open-headunit announces it (protocol/messages/
         * ServiceDiscoveryResponse.kt, AGPL-3.0, commit 93c065dd): an empty service on channel 9.
         */
        private val mediaPlaybackStatus: Control.Service = Control.Service.newBuilder().apply {
            id = Channel.ID_MPB
            mediaPlaybackService = Control.Service.MediaPlaybackStatusService.newBuilder().build()
        }.build()

        /** Behind a switch: the phone status channel, an empty service on channel 12, as the music one. */
        private val phoneStatus: Control.Service = Control.Service.newBuilder().apply {
            id = Channel.ID_PHONE
            phoneStatusService = Control.Service.PhoneStatusService.newBuilder().build()
        }.build()

        /**
         * Behind a switch: the instrument cluster feed (after MOTO-HUB's ServiceDiscoveryResponse.kt), maneuver
         * codes only (the dash draws its own glyphs), at most one update a second.
         */
        private fun navigationStatus(): Control.Service = Control.Service.newBuilder().apply {
            id = Channel.ID_NAV
            navigationStatusService = Control.Service.NavigationStatusService.newBuilder().apply {
                minimumIntervalMs = NAV_INTERVAL_MS
                type = Control.Service.NavigationStatusService.ClusterType.ImageCodesOnly
            }.build()
        }.build()

        private const val NAV_INTERVAL_MS = 1_000

        private fun sensors(): Control.Service = Control.Service.newBuilder().apply {
            id = Channel.ID_SEN
            sensorSourceService = Control.Service.SensorSourceService.newBuilder().apply {
                for (type in listOf(Sensors.SensorType.DRIVING_STATUS, Sensors.SensorType.NIGHT)) {
                    addSensors(Control.Service.SensorSourceService.Sensor.newBuilder().setType(type))
                }
            }.build()
        }.build()

        private fun video(config: AaConfig): Control.Service = Control.Service.newBuilder().apply {
            id = Channel.ID_VID
            mediaSinkService = Control.Service.MediaSinkService.newBuilder().apply {
                availableType = Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                audioType = Media.AudioStreamType.NONE
                availableWhileInCall = true
                addVideoConfigs(
                    Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                        codecResolution = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
                        frameRate = Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30
                        density = config.density
                        marginWidth = config.margins.marginW
                        marginHeight = config.margins.marginH
                        videoCodecType = Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                    },
                )
            }.build()
        }.build()

        /** The keys and the wheel; with [AaConfig.touch], also a touch screen the size of the video (off: bytes as before). */
        private fun input(config: AaConfig): Control.Service = Control.Service.newBuilder().apply {
            id = Channel.ID_INP
            inputSourceService = Control.Service.InputSourceService.newBuilder().addAllKeycodesSupported(SUPPORTED_KEYCODES).apply {
                if (config.touch) {
                    touchscreen = Control.Service.InputSourceService.TouchConfig.newBuilder()
                        .setWidth(AaVideo.WIDTH).setHeight(AaVideo.HEIGHT).build()
                }
            }.build()
        }.build()
    }
}

/** The sound services announced to Android Auto: the system sink, the media and guidance sinks (web server) and the microphone. */
private object AudioServices {
    private const val AUDIO_SAMPLE_RATE = 16_000
    private const val MEDIA_SAMPLE_RATE = 48_000
    private const val AUDIO_BITS = 16

    fun systemAudio(): Control.Service = Control.Service.newBuilder().apply {
        id = Channel.ID_AU2
        mediaSinkService = Control.Service.MediaSinkService.newBuilder().apply {
            availableType = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
            audioType = Media.AudioStreamType.SYSTEM
            addAudioConfigs(pcmMono())
        }.build()
    }.build()

    /** The music and the other media sound, when REau takes it (the web server's sound): 48 kHz stereo, on channel 6. */
    fun mediaAudio(): Control.Service = Control.Service.newBuilder().apply {
        id = Channel.ID_AUD
        mediaSinkService = Control.Service.MediaSinkService.newBuilder().apply {
            availableType = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
            audioType = Media.AudioStreamType.MEDIA
            addAudioConfigs(pcm(MEDIA_SAMPLE_RATE, 2))
        }.build()
    }.build()

    /** The navigation voice, when REau takes it: 16 kHz mono, on channel 4. */
    fun guidanceAudio(): Control.Service = Control.Service.newBuilder().apply {
        id = Channel.ID_AU1
        mediaSinkService = Control.Service.MediaSinkService.newBuilder().apply {
            availableType = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
            audioType = Media.AudioStreamType.GUIDANCE
            addAudioConfigs(pcm(AUDIO_SAMPLE_RATE, 1))
        }.build()
    }.build()

    /** The assistant's voice (Gemini), when REau takes it: 16 kHz mono, on channel 14. An experiment: what Android Auto does with it is seen on the phone. */
    fun speechAudio(): Control.Service = Control.Service.newBuilder().apply {
        id = Channel.ID_SPE
        mediaSinkService = Control.Service.MediaSinkService.newBuilder().apply {
            availableType = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
            audioType = Media.AudioStreamType.SPEECH
            addAudioConfigs(pcm(AUDIO_SAMPLE_RATE, 1))
        }.build()
    }.build()

    fun microphone(): Control.Service = Control.Service.newBuilder().apply {
        id = Channel.ID_MIC
        mediaSourceService = Control.Service.MediaSourceService.newBuilder().apply {
            type = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
            audioConfig = pcmMono()
        }.build()
    }.build()

    fun pcmMono(): Media.AudioConfiguration = pcm(AUDIO_SAMPLE_RATE, 1)

    fun pcm(rate: Int, channels: Int): Media.AudioConfiguration = Media.AudioConfiguration.newBuilder().apply {
        sampleRate = rate
        numberOfBits = AUDIO_BITS
        numberOfChannels = channels
    }.build()
}
