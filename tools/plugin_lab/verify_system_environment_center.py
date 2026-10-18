#!/usr/bin/env python3
"""Guard the parent lifecycle against the retired presentation-service dependency."""
import json
from pathlib import Path
root = Path(__file__).resolve().parents[2]
manifest = json.loads((root / "plugin-lab/packages/system-environment-center/plugin.json").read_text())
entry = (root / "plugin-lab/plugins/system-environment-center/src/main/java/com/ai/limbs/plugins/systemenvironment/SystemEnvironmentCenterEntry.kt").read_text()
retired = "system.plugin_center.ui_accessories"
assert all(item["id"] != retired for item in manifest["dependencies"]["services"]), "Retired UI service cannot gate parent activation"
assert retired not in entry, "Parent mount must not resolve the retired UI accessory service"
assert "host.childExtensions.publishPoint(" in entry, "Generic child extension point is required"
assert 'PAGE_SLOT_ACTIONS_METADATA to pageSlotActions' in entry, "Provider entry declarations must remain available"
print("System environment Host/Resident activation contract: PASS")
