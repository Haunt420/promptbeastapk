package app.promptbeast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import promptbeast.Composer
import promptbeast.Project

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Project(boxes = linkedMapOf("main" to "a {red|green|blue} fox"))
        val out = Composer.assemble(p, 1).prompt
        setContent { Text(out) }
    }
}
