import copy
import importlib.util
import json
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('settings_contract',ROOT/'scripts/verify-native-settings.py')
settings=importlib.util.module_from_spec(spec);spec.loader.exec_module(settings)
class SettingsContractTest(unittest.TestCase):
    def setUp(self):
        self.variant=json.loads((ROOT/'host-applemusic/src/main/resources/host-profiles/7.0.0-1614.json').read_text())['settingsVariants'][0]
        self.classes={name:{'method_access':dict(c['methods']),'fields':{f:v['descriptor'] for f,v in c['fields'].items()},'field_access':{f:v['access'] for f,v in c['fields'].items()}} for name,c in self.variant['verification']['classes'].items()}
    def test_verified_inventory_and_contracts_agree(self):
        self.assertEqual([],settings.verify(self.classes,self.variant))
        self.assertFalse(self.variant['verification']['deviceValidated'])
    def test_partial_callback_constructor_or_invocation_drift_fails_closed(self):
        for key,value in [('callbackClass','unverified.Function'),('composerClass','unverified.Composer')]:
            changed=copy.deepcopy(self.variant);changed[key]=value
            if key=='composerClass':changed['members']['methods']['compose']['parameters'][0]=value
            self.assertTrue(settings.verify(self.classes,changed))
        changed=copy.deepcopy(self.classes)
        name='Lcom/apple/android/music/settings2/model/SettingsViewModel;'
        changed[name]['method_access'].clear()
        self.assertTrue(settings.verify(changed,self.variant))
