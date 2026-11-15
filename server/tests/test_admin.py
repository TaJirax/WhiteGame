import io
import json
import unittest
import urllib.error
from unittest.mock import patch

from server import admin


class AdminTests(unittest.TestCase):
    token = "a-long-admin-token-known-only-to-operators"
    key = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="

    def test_normalizes_https_and_rejects_credential_or_path_ambiguity(self):
        self.assertEqual(admin.normalize_server(" HTTPS://HOST.EXAMPLE:443/base/ "), "https://host.example/base")
        self.assertEqual(admin.normalize_server("https://[::1]:8443"), "https://[::1]:8443")
        for value in ("http://host", "https://user:secret@host", "https://host?token=a",
                      "https://host#token", "https://host?", "https://host#", "https://host/%2e", "https://host/../", "https://host:0"):
            with self.subTest(value=value), self.assertRaises(admin.AdminError):
                admin.normalize_server(value)

    def test_list_sends_bearer_in_header_without_credentials_in_url(self):
        seen = []
        def opened(request, **options):
            seen.append(request)
            self.assertEqual(options, {"timeout": 30})
            return io.BytesIO(b'{"peers":[]}')
        self.assertEqual(admin.request("https://host.example", self.token, open_url=opened), {"peers": []})
        self.assertEqual(seen[0].get_method(), "GET")
        self.assertEqual(seen[0].get_header("Authorization"), "Bearer " + self.token)
        self.assertEqual(seen[0].full_url, "https://host.example/v1/peers")

    def test_revoke_encodes_the_public_key_and_requires_valid_key(self):
        seen = []
        def opened(request, **_options):
            seen.append(request)
            return io.BytesIO(b'{"revoked":true}')
        admin.request("https://host.example", self.token, self.key, opened)
        self.assertEqual(seen[0].get_method(), "DELETE")
        self.assertTrue(seen[0].full_url.endswith("%3D"))
        with self.assertRaises(admin.AdminError):
            admin.request("https://host.example", self.token, "invalid", opened)
        self.assertEqual(len(seen), 1)

    def test_redirects_are_not_followed_and_error_bodies_are_not_echoed(self):
        self.assertIsNone(admin.NoRedirect().redirect_request(None, None, 302, None, None, "https://other.example"))
        def denied(*_args, **_kwargs):
            raise urllib.error.HTTPError("https://host.example", 403, "secret " + self.token, None, io.BytesIO(self.token.encode()))
        with self.assertRaisesRegex(admin.AdminError, r"HTTP 403") as caught:
            admin.request("https://host.example", self.token, open_url=denied)
        self.assertNotIn(self.token, str(caught.exception))

    def test_cli_prompts_for_token_instead_of_accepting_secret_argument(self):
        with patch.object(admin.getpass, "getpass", return_value=self.token) as prompt, \
                patch.object(admin, "request", return_value={"peers": []}) as call, \
                patch("sys.stdout", new_callable=io.StringIO) as output:
            self.assertEqual(admin.main(["--server", "https://host.example", "list"]), 0)
        prompt.assert_called_once_with("Admin token: ")
        call.assert_called_once_with("https://host.example", self.token, None)
        self.assertEqual(json.loads(output.getvalue()), {"peers": []})

    def test_invalid_token_or_response_cannot_be_accepted(self):
        with self.assertRaises(admin.AdminError):
            admin.request("https://host.example", "invalid\r\nheader", open_url=lambda *_a, **_k: self.fail("Must not open"))
        for body in (b"invalid", b"{}", b"[]"):
            with self.subTest(body=body), self.assertRaises(admin.AdminError):
                admin.request("https://host.example", self.token, open_url=lambda *_a, **_k: io.BytesIO(body))


if __name__ == "__main__":
    unittest.main()
