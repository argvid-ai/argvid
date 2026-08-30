package ai.argvid.gen0

import android.app.Application
import ai.argvid.gen0.media.db.Gen0Database
import androidx.room.Room

class Gen0Application : Application() {
    val database: Gen0Database by lazy {
        Room.databaseBuilder(this, Gen0Database::class.java, "gen0.db")
            .build()
    }
}
