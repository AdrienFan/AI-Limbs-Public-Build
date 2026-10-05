#!/usr/bin/env python3
"""Static package/entry checks; gameplay tests run in the cloud Gradle task."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[2]
module = ROOT / "plugin-lab/extensions/draw-guess"
manifest = json.loads((ROOT / "plugin-lab/packages/draw-guess/extension.json").read_text())
assert manifest["format"] == "AIL_EXTENSION_V1"
assert manifest["target"] == {"plugin_id": "plugin.art.studio", "extension_point": "plugin.art.studio.extension_menu", "api": 1}
assert manifest["permissions"]["host_capabilities"] == []
assert manifest["runtime"]["kind"] == "android_child"
assert manifest["version"] == re.search(r'versionName\s*=\s*"([^"]+)"', (module / "build.gradle.kts").read_text())[1]
entry_class = manifest["runtime"]["config"]["entry_class"]
entry = module / "src/main/java" / (entry_class.replace(".", "/") + ".kt")
source = entry.read_text()
assert "ChildExtensionEntry" in source and "publishAiIngressDiscovery" in source
assert "Consumer<InProcessUiStateProvider>(game::connect)" in source
assert 'Player.LANER, event' in source and 'Player.AWEI, eventId' in source
assert "suggestedParamsJson = example.toString()" in source
# Mirror Child Runtime admission: installation identity is not the capability prefix.
namespace = "plugin." + manifest["extension_id"].rsplit(".", 1)[-1]
assert namespace == "plugin.draw_guess"
assert 'internal val GAME_CAPABILITIES = "plugin.${GAME_ID.substringAfterLast(\'.\')}"' in source
assert 'id = "$GAME_CAPABILITIES.$event"' in source
assert '"$GAME_CAPABILITIES.ready"' in source and '"$GAME_CAPABILITIES.view"' in source
assert '"$GAME_ID.$event"' not in source
readme = (module / "README.md").read_text()
for capability in re.findall(r'"capability":"([^" ]+)"', readme):
    assert capability.startswith(namespace + "."), capability
parent = json.loads((ROOT / "plugin-lab/packages/art-studio/plugin.json").read_text())
assert "plugin.art.studio.interactions" in parent["provides"]["providers"]
assert tuple(map(int, parent["version"].split("."))) >= (0, 2, 98)
print("Draw Guess entry, parent contract, ownership and package declarations OK")
