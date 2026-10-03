package juricabi.com.groundtrack.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import juricabi.com.groundtrack.BuildConfig
import juricabi.com.groundtrack.R

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