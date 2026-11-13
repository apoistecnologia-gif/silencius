package br.com.apois.app

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(SilencePlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
