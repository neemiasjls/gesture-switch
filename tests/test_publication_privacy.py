import unittest
import configparser

from diagnostics.check_public_files import extract_private_values, inspect_text


class PublicationPrivacyTest(unittest.TestCase):
    def test_known_private_value_is_detected_without_echoing_it(self):
        secret = "test-only-secret-value"
        result = inspect_text("token=" + secret, {secret})
        self.assertEqual(result, [(1, "valor da configuracao privada")])
        self.assertNotIn(secret, str(result))

    def test_empty_configuration_template_passes(self):
        self.assertEqual(inspect_text("[smartthings]\ntoken=\ntargets=\n", set()), [])

    def test_initial_setup_placeholders_are_not_treated_as_secrets(self):
        template = configparser.ConfigParser(interpolation=None)
        template.read_string("[device_1]\nsuffix=FINAL_DO_ID_1\n[network]\ncidr=REDE_LOCAL_CIDR\n")
        self.assertEqual(extract_private_values(template, template), set())

    def test_private_identifiers_are_flagged(self):
        # Assemble examples so this test file itself contains no full identifier.
        cases = [("ip=" + ".".join(("10", "2", "3", "4")), "IP numerico"),
                 ("user=" + "example" + "@" + "example.org", "email"),
                 ("mac=" + ":".join(("aa", "bb", "cc", "dd", "ee", "ff")), "MAC")]
        for text, label in cases:
            with self.subTest(label=label):
                self.assertIn((1, label), inspect_text(text, set()))
