package juricabi.com.telemetry.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import juricabi.com.telemetry.BuildConfig
import juricabi.com.telemetry.R

class SettingsActivity: AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings)

        val toolbar : Toolbar = findViewById(R.id.toolbar)

        toolbar?.title = "Settings " + BuildConfig.VERSION_NAME

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.parent, PrefsFragment())
            .commit()
    }
}