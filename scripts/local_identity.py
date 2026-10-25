"""Loopback-only OIDC/JWKS test fixture. This is not Cognito or a production IdP."""
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import threading
import time
from urllib.parse import parse_qs, urlencode, urlsplit


def encode(value):
    return base64.urlsafe_b64encode(value).rstrip(b'=')


class LocalIdentity:
    client_id = 'local-fixture-client'

    def __init__(self, frontend_origin='http://localhost:4200'):
        scratch = Path(os.environ.get('TMPDIR', str(Path.home() / '.cache/pedidos360-tests')))
        scratch.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(prefix='pedidos360-oidc-', dir=scratch)
        self.key = Path(self.directory.name) / 'ephemeral-key.pem'
        subprocess.run(['openssl', 'genpkey', '-quiet', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048',
                        '-out', str(self.key)], check=True, capture_output=True)
        self.key.chmod(0o600)
        output = subprocess.run(['openssl', 'rsa', '-in', str(self.key), '-noout', '-modulus'],
                                check=True, capture_output=True, text=True).stdout
        modulus = int(output.strip().split('=')[1], 16)
        self.jwk = {'kty': 'RSA', 'alg': 'RS256', 'use': 'sig', 'kid': 'local-ephemeral-key',
                    'n': encode(modulus.to_bytes((modulus.bit_length() + 7) // 8, 'big')).decode(), 'e': 'AQAB'}
        self.frontend_origin = frontend_origin
        self.codes = {}
        self.counters = {'authorization': 0, 'token': 0, 'logout': 0, 'pkce_verified': 0}
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def respond(self, value, status=200):
                body = json.dumps(value).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Cache-Control', 'no-store')
                self.send_header('Access-Control-Allow-Origin', fixture.frontend_origin)
                self.end_headers()
                self.wfile.write(body)

            def do_OPTIONS(self):
                self.send_response(204)
                self.send_header('Access-Control-Allow-Origin', fixture.frontend_origin)
                self.send_header('Access-Control-Allow-Methods', 'GET,POST,OPTIONS')
                self.send_header('Access-Control-Allow-Headers', 'Content-Type,Authorization')
                self.end_headers()

            def do_GET(self):
                location = urlsplit(self.path)
                if location.path == '/.well-known/openid-configuration':
                    return self.respond({'issuer': fixture.issuer, 'authorization_endpoint': fixture.issuer + '/authorize',
                        'token_endpoint': fixture.issuer + '/token', 'jwks_uri': fixture.issuer + '/.well-known/jwks.json',
                        'userinfo_endpoint': fixture.issuer + '/userinfo', 'end_session_endpoint': fixture.issuer + '/logout',
                        'response_types_supported': ['code'], 'subject_types_supported': ['public'],
                        'id_token_signing_alg_values_supported': ['RS256'], 'code_challenge_methods_supported': ['S256'],
                        'token_endpoint_auth_methods_supported': ['none'], 'scopes_supported': ['openid', 'email', 'pedidos/read', 'pedidos/write', 'rabbitmq/admin']})
                if location.path == '/.well-known/jwks.json':
                    return self.respond({'keys': [fixture.jwk]})
                if location.path == '/userinfo':
                    return self.respond({'sub': 'synthetic-local-user', 'email': 'synthetic@example.test'})
                args = {key: values[0] for key, values in parse_qs(location.query).items()}
                if location.path == '/authorize':
                    if (args.get('client_id') != fixture.client_id or args.get('redirect_uri') != fixture.frontend_origin + '/callback'
                            or args.get('response_type') != 'code' or args.get('code_challenge_method') != 'S256'
                            or not all(args.get(key) for key in ['state', 'nonce', 'code_challenge'])):
                        return self.respond({'error': 'invalid_request'}, 400)
                    code = secrets.token_urlsafe(24)
                    fixture.codes[code] = args
                    fixture.counters['authorization'] += 1
                    self.send_response(302)
                    self.send_header('Location', args['redirect_uri'] + '?' + urlencode({'code': code, 'state': args['state']}))
                    self.end_headers()
                    return
                if location.path == '/logout':
                    target = args.get('logout_uri', args.get('post_logout_redirect_uri', ''))
                    if target != fixture.frontend_origin + '/':
                        return self.respond({'error': 'invalid_logout_uri'}, 400)
                    fixture.counters['logout'] += 1
                    self.send_response(302)
                    self.send_header('Location', target)
                    self.end_headers()
                    return
                return self.respond({'error': 'not_found'}, 404)

            def do_POST(self):
                if urlsplit(self.path).path != '/token':
                    return self.respond({'error': 'not_found'}, 404)
                length = int(self.headers.get('Content-Length', '0'))
                if length > 10000:
                    return self.respond({'error': 'invalid_request'}, 400)
                args = {key: values[0] for key, values in parse_qs(self.rfile.read(length).decode()).items()}
                original = fixture.codes.pop(args.get('code'), None)
                challenge = encode(hashlib.sha256(args.get('code_verifier', '').encode()).digest()).decode()
                if (not original or original['code_challenge'] != challenge or args.get('client_id') != fixture.client_id
                        or args.get('redirect_uri') != original['redirect_uri'] or args.get('grant_type') != 'authorization_code'):
                    return self.respond({'error': 'invalid_grant'}, 400)
                fixture.counters['pkce_verified'] += 1
                fixture.counters['token'] += 1
                scope = original.get('scope', '')
                return self.respond({'access_token': fixture.access_token(scope), 'token_type': 'Bearer', 'expires_in': 300,
                    'scope': scope, 'id_token': fixture.signed({'aud': fixture.client_id, 'nonce': original['nonce'],
                        'email': 'synthetic@example.test', 'token_use': 'id'})})

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.issuer = f'http://127.0.0.1:{self.server.server_port}'
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def signed(self, overrides=None):
        now = int(time.time())
        claims = {'iss': self.issuer, 'sub': 'synthetic-local-user', 'iat': now, 'exp': now + 300}
        claims.update(overrides or {})
        content = b'.'.join([encode(json.dumps({'alg': 'RS256', 'kid': self.jwk['kid']}).encode()), encode(json.dumps(claims).encode())])
        signature = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(self.key)], input=content,
                                   check=True, capture_output=True).stdout
        return (content + b'.' + encode(signature)).decode()

    def access_token(self, scope='pedidos/read pedidos/write rabbitmq/admin', **overrides):
        claims = {'client_id': self.client_id, 'token_use': 'access', 'scope': scope}
        claims.update(overrides)
        return self.signed(claims)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.directory.cleanup()
