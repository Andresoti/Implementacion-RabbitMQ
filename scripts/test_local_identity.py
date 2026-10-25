"""Tests for the loopback-only identity fixture used by local integration checks."""
import base64
import hashlib
import json
import unittest
import urllib.error
import urllib.parse
import urllib.request

from local_identity import LocalIdentity


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        return None


class IdentityFixtureTests(unittest.TestCase):
    def setUp(self):
        self.fixture = LocalIdentity()
        self.verifier = 'fixture-verifier-with-enough-entropy-0123456789'

    def tearDown(self):
        self.fixture.close()

    def authorize(self):
        args = {'client_id': self.fixture.client_id, 'redirect_uri': 'http://localhost:4200/callback',
                'response_type': 'code', 'scope': 'openid email pedidos/read pedidos/write',
                'state': 'transaction-state', 'nonce': 'transaction-nonce', 'code_challenge_method': 'S256',
                'code_challenge': base64.urlsafe_b64encode(hashlib.sha256(self.verifier.encode()).digest()).rstrip(b'=').decode()}
        opener = urllib.request.build_opener(NoRedirect())
        with self.assertRaises(urllib.error.HTTPError) as result:
            opener.open(self.fixture.issuer + '/authorize?' + urllib.parse.urlencode(args))
        self.assertEqual(result.exception.code, 302)
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(result.exception.headers['Location']).query)
        result.exception.close()
        self.assertEqual(query['state'], ['transaction-state'])
        return query['code'][0]

    def redeem(self, code, verifier):
        data = urllib.parse.urlencode({'grant_type': 'authorization_code', 'code': code,
            'client_id': self.fixture.client_id, 'redirect_uri': 'http://localhost:4200/callback',
            'code_verifier': verifier}).encode()
        with urllib.request.urlopen(self.fixture.issuer + '/token', data=data) as response:
            return json.load(response)

    def test_discovery_and_jwks_are_real_loopback_http(self):
        with urllib.request.urlopen(self.fixture.issuer + '/.well-known/openid-configuration') as response:
            metadata = json.load(response)
        self.assertEqual(metadata['issuer'], self.fixture.issuer)
        self.assertEqual(metadata['code_challenge_methods_supported'], ['S256'])
        with urllib.request.urlopen(metadata['jwks_uri']) as response:
            keys = json.load(response)
        self.assertEqual(keys['keys'][0]['kid'], 'local-ephemeral-key')
        self.assertNotIn('d', keys['keys'][0])

    def test_valid_pkce_yields_signed_tokens_and_preserves_nonce(self):
        tokens = self.redeem(self.authorize(), self.verifier)
        self.assertEqual(len(tokens['access_token'].split('.')), 3)
        payload = tokens['id_token'].split('.')[1]
        claims = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
        self.assertEqual(claims['nonce'], 'transaction-nonce')
        self.assertEqual(claims['aud'], self.fixture.client_id)
        self.assertEqual(self.fixture.counters['pkce_verified'], 1)

    def test_wrong_verifier_is_rejected(self):
        with self.assertRaises(urllib.error.HTTPError) as result:
            self.redeem(self.authorize(), 'incorrect-verifier')
        self.assertEqual(result.exception.code, 400)
        result.exception.close()
        self.assertEqual(self.fixture.counters['pkce_verified'], 0)

    def test_authorization_code_cannot_be_replayed(self):
        code = self.authorize()
        self.redeem(code, self.verifier)
        with self.assertRaises(urllib.error.HTTPError) as result:
            self.redeem(code, self.verifier)
        self.assertEqual(result.exception.code, 400)
        result.exception.close()

    def test_logout_rejects_unregistered_redirect(self):
        with self.assertRaises(urllib.error.HTTPError) as result:
            urllib.request.urlopen(self.fixture.issuer + '/logout?logout_uri=https://untrusted.example')
        self.assertEqual(result.exception.code, 400)
        result.exception.close()


if __name__ == '__main__':
    unittest.main()
