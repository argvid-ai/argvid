"""Host wiring guards; Android lifecycle/PlayerView behavior needs instrumentation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1] / "src"


class LifecycleOwnershipTest(unittest.TestCase):
    def test_runtime_is_not_owned_by_composition(self):
        app = (ROOT / "app/src/main/kotlin/ai/argvid/gen0/App.kt").read_text()
        self.assertNotIn("remember { SessionRuntime", app)
        self.assertNotIn("remember { Media3MomentPlayer", app)
        self.assertIn("AppRuntimeViewModel", app)

    def test_existing_surface_updates_when_player_is_recreated(self):
        source = (ROOT / "feature/today/src/main/kotlin/ai/argvid/gen0/today/MomentPlayer.kt").read_text()
        self.assertIn("collectAsState", source)
        self.assertIn("update =", source)
        self.assertIn("view.player = currentPlayer", source)


if __name__ == "__main__":
    unittest.main()
