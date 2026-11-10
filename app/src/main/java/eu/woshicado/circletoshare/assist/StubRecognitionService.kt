package eu.woshicado.circletoshare.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The system requires an assistant to declare a recognition service.
 * This app never does voice recognition, so every request is rejected —
 * for good, not "busy, try again": chosen as the voice input (see
 * res/xml/recognition_service.xml), this is what other apps' requests get.
 */
class StubRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: Exception) {
        }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
