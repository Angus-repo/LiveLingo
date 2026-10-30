package app.livelingo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.livelingo.app.LiveLingoApp
import app.livelingo.app.AndroidSessionRegistry

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val session = AndroidSessionRegistry.attach(this)
        setContent { LiveLingoApp(session.services, session.controller) }
    }
}
