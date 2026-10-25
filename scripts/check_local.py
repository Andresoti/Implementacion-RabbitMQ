"""Real local EV2 integration checks; disposable resources, never AWS/Cognito proof.

Run after Maven verification. Requires Docker Compose, PostgreSQL/RabbitMQ images,
Java 17 and OpenSSL. No credentials are read from the project or printed.
"""
import argparse
import base64
from contextlib import contextmanager
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from local_identity import LocalIdentity

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / 'pedidos360-backend'


def wait_for(check, label, timeout=60):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except (OSError, urllib.error.URLError, AssertionError) as error:
            last = type(error).__name__
        time.sleep(0.2)
    raise AssertionError(f'Timed out: {label}; last error category={last}')


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


class LocalChecks:
    def __init__(self, frontend_port=4200):
        if not 1 <= frontend_port <= 65535:
            raise ValueError('Invalid local frontend port')
        self.frontend_port = frontend_port
        self.frontend_origin = 'http://localhost:' + str(frontend_port)
        self.project = 'pedidos360-integration-' + secrets.token_hex(5)
        self.database = self.project + '-database'
        self.env = os.environ.copy()
        self.env.update(RABBITMQ_USERNAME='local-integration',
                        RABBITMQ_PASSWORD=secrets.token_urlsafe(24),
                        RABBITMQ_ERLANG_COOKIE=secrets.token_hex(24))
        for name in ['RABBITMQ_AMQP_PORT1', 'RABBITMQ_AMQP_PORT2',
                     'RABBITMQ_MANAGEMENT_PORT1', 'RABBITMQ_MANAGEMENT_PORT2']:
            self.env[name] = str(free_port())
        self.database_password = secrets.token_urlsafe(24)
        self.compose_command = ['docker', 'compose', '--project-name', self.project,
                                '-f', str(ROOT / 'compose.rabbitmq.yaml')]
        self.identity = None
        self.processes = {}
        self.logs = {}
        self.created_cluster = False
        self.created_database = False
        self.created_frontend = False
        self.frontend_container = self.project + '-frontend'
        self.work = tempfile.TemporaryDirectory(prefix=self.project + '-', dir=os.environ.get('TMPDIR'))
        self.ports = {module: free_port() for module in ['orders-service', 'worker-service', 'rabbit-admin-service']}
        self.db_port = free_port()
        self.passed = []

    def run(self, args, *, input=None, env=None, timeout=180, allow_missing=False):
        result = subprocess.run(args, input=input, env=env or self.env,
                                capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            if allow_missing and any(marker in result.stderr for marker in ['No such object:', 'No such container:']):
                return None
            # Commands never contain real credentials; do not include environment values.
            raise RuntimeError(f'Command failed ({args[0]}): {result.stderr[-2500:]} {result.stdout[-2500:]}')
        return result.stdout.strip()

    def compose(self, *args):
        return self.run(self.compose_command + list(args), timeout=240)

    def http(self, base, path, *, method='GET', body=None, token=None, status=200):
        headers = {'Accept': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers['Content-Type'] = 'application/json'
        request = urllib.request.Request(base + path, data=data, method=method, headers=headers)
        try:
            response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read()
            assert response.status == status, f'{method} {path}: {response.status}, expected {status}'
            return json.loads(payload) if payload and 'application/json' in response.headers.get('Content-Type', '') else payload.decode()

    def broker(self, path, *, method='GET', body=None, status=200):
        value = self.env['RABBITMQ_USERNAME'] + ':' + self.env['RABBITMQ_PASSWORD']
        headers = {'Authorization': 'Basic ' + base64.b64encode(value.encode()).decode()}
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            headers['Content-Type'] = 'application/json'
        try:
            response = urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:' + self.env['RABBITMQ_MANAGEMENT_PORT1'] + '/api' + path,
                            data=data, method=method, headers=headers), timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read()
            assert response.status == status, f'Broker {method} {path}: {response.status}, expected {status}'
            return json.loads(payload) if payload else None

    def sql(self, database, statement):
        return self.run(['docker', 'exec', self.database, 'psql', '-X', '-q', '-A', '-t',
                         '-v', 'ON_ERROR_STOP=1', '-U', 'pedidos360', '-d', database, '-c', statement])

    def start_application(self, module):
        jar = BACKEND / module / 'target' / (module + '-0.0.1-SNAPSHOT.jar')
        assert jar.is_file(), f'Build required: {jar}'
        environment = self.env.copy()
        environment.update(SERVER_PORT=str(self.ports[module]), COGNITO_ISSUER=self.identity.issuer,
                           COGNITO_CLIENT_ID=self.identity.client_id, COGNITO_AUDIENCE='',
                           RABBITMQ_ADDRESSES=','.join('127.0.0.1:' + self.env[name]
                               for name in ['RABBITMQ_AMQP_PORT1', 'RABBITMQ_AMQP_PORT2']),
                           CORS_ALLOWED_ORIGINS=self.frontend_origin,
                           DB_USERNAME='pedidos360', DB_PASSWORD=self.database_password, DB_DDL_AUTO='validate')
        if module != 'rabbit-admin-service':
            database = 'pedidos360_orders' if module == 'orders-service' else 'pedidos360_worker'
            environment['DB_URL'] = f'jdbc:postgresql://127.0.0.1:{self.db_port}/{database}'
        log_path = Path(self.work.name) / (module + '.log')
        handle = log_path.open('w')
        args = ['java', '-jar', str(jar), '--server.address=127.0.0.1']
        if module == 'worker-service':
            args.append("--spring.datasource.hikari.connection-init-sql=SET lock_timeout='200ms'")
        process = subprocess.Popen(args, env=environment, stdout=handle, stderr=subprocess.STDOUT)
        handle.close()
        self.processes[module] = process
        self.logs[module] = log_path
        def ready():
            if process.poll() is not None:
                raise RuntimeError(f'{module} exited: {log_path.read_text()[-6000:]}')
            with urllib.request.urlopen(self.base(module) + '/api/public/ping', timeout=5) as response:
                return response.status == 200
        wait_for(ready, module + ' readiness')

    def stop_application(self, module):
        process = self.processes.get(module)
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def base(self, module):
        return 'http://127.0.0.1:' + str(self.ports[module])

    def event(self, **overrides):
        value = {'eventId': str(uuid.uuid4()), 'orderId': 900,
                 'customer': 'Synthetic integration', 'product': 'Test item', 'quantity': 1,
                 'occurredAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}
        value.update(overrides)
        return value

    def publish(self, exchange, route, payload, message_id=None, routed=True):
        body = {'routing_key': route, 'payload': payload, 'payload_encoding': 'string',
                'properties': {'content_type': 'application/json', 'delivery_mode': 2,
                               'message_id': message_id or str(uuid.uuid4())}}
        result = self.broker('/exchanges/%2F/' + urllib.parse.quote(exchange or 'amq.default', safe='') + '/publish', method='POST', body=body)
        assert result['routed'] is routed, 'Unexpected exchange routing outcome'

    def results(self):
        return self.http(self.base('worker-service'), '/api/results', token=self.identity.access_token())

    def note(self, label):
        self.passed.append(label)
        print('PASS: ' + label, flush=True)

    def start(self):
        self.identity = LocalIdentity(self.frontend_origin)
        self.compose('config', '--quiet')
        self.created_cluster = True
        self.compose('up', '-d', '--wait', '--wait-timeout', '180')
        nodes = json.loads(self.compose('exec', '-T', 'rabbit1', 'rabbitmqctl', 'cluster_status', '--formatter=json'))
        assert sorted(nodes['running_nodes']) == ['rabbit@rabbit1', 'rabbit@rabbit2']
        self.note('two real RabbitMQ nodes in one cluster')
        env = self.env.copy()
        env['POSTGRES_PASSWORD'] = self.database_password
        self.run(['docker', 'run', '--detach', '--rm', '--name', self.database,
                  '--label', 'hermes.task=pedidos360-local-integration',
                  '--publish', f'127.0.0.1:{self.db_port}:5432', '--env', 'POSTGRES_DB=postgres',
                  '--env', 'POSTGRES_USER=pedidos360', '--env', 'POSTGRES_PASSWORD',
                  '--volume', str(ROOT / 'infra/postgres/init.sh') + ':/docker-entrypoint-initdb.d/001-init.sh:ro',
                  '--volume', str(BACKEND / 'database') + ':/schemas:ro', 'postgres:17'], env=env)
        self.created_database = True
        def database_ready():
            probe = subprocess.run(['docker', 'exec', self.database, 'psql', '-X', '-q', '-A', '-t',
                                    '-U', 'pedidos360', '-d', 'pedidos360_worker', '-c',
                                    "select to_regclass('processing_result')"], capture_output=True, text=True)
            return probe.returncode == 0 and probe.stdout.strip() == 'processing_result'
        wait_for(database_ready, 'PostgreSQL schema bootstrap')
        for module in self.ports:
            self.start_application(module)
        self.note('three microservices start with real PostgreSQL schemas and fail-closed JWT filters')

    def check(self):
        orders = self.base('orders-service')
        worker = self.base('worker-service')
        admin = self.base('rabbit-admin-service')
        token = self.identity.access_token()
        for base, path in [(orders, '/api/pedidos'), (worker, '/api/results'), (admin, '/api/rabbitmq/queues')]:
            self.http(base, path, status=401)
            self.http(base, path, token='malformed', status=401)
        self.http(orders, '/api/pedidos', token=self.identity.access_token('pedidos/write'), status=403)
        self.http(admin, '/api/rabbitmq/queues', method='POST', body={'name': 'denied', 'durable': True},
                  token=self.identity.access_token('pedidos/read pedidos/write'), status=403)
        self.http(orders, '/api/pedidos', token=self.identity.access_token(client_id='wrong-client'), status=401)
        self.note('real HTTP 401/403 and signed JWT client validation')
        self.http(orders, '/api/pedidos', method='POST', body={'cliente': '', 'producto': 'Item', 'cantidad': 0}, token=token, status=400)
        self.http(orders, '/api/pedidos', method='POST', body={'cliente': 'Bad\0name', 'producto': 'Item', 'cantidad': 1}, token=token, status=400)
        order = self.http(orders, '/api/pedidos', method='POST', body={'cliente': 'Synthetic integration', 'producto': 'Test item', 'cantidad': 2}, token=token, status=201)
        assert order['estado'] == 'CREATED'
        results = wait_for(lambda: (values if len(values := self.results()) == 3 else None), 'three successful domain effects')
        assert {item['domain'] for item in results} == {'NOTIFICATION', 'TICKET', 'DOCUMENT'}
        assert all(item['orderId'] == order['id'] for item in results)
        assert self.sql('pedidos360_orders', 'select count(*) from outbox where published_at is null') == '0'
        self.note('order transaction, confirmed outbox publication and three persisted domain results')
        queues = self.broker('/queues/%2F')
        expected = {'pedidos360.' + name for name in ['notifications', 'documents', 'tickets', 'notifications.dlq', 'documents.dlq', 'tickets.dlq']}
        assert {item['name'] for item in queues} == expected
        for name in ['notifications', 'documents', 'tickets']:
            queue = next(item for item in queues if item['name'] == 'pedidos360.' + name)
            assert queue['arguments']['x-dead-letter-exchange'] == 'pedidos360.dead'
        exchanges = {item['name']: item['type'] for item in self.broker('/exchanges/%2F')}
        assert exchanges['pedidos360.orders'] == 'topic' and exchanges['pedidos360.tasks'] == 'direct'
        self.note('three application queues, three DLQs and configured direct/topic exchanges')
        payload = self.sql('pedidos360_orders', 'select payload from outbox order by id limit 1')
        event_id = json.loads(payload)['eventId']
        domains = ['notifications', 'documents', 'tickets']
        before = {domain: self.broker('/queues/%2F/pedidos360.' + domain).get('message_stats', {}).get('ack', 0) for domain in domains}
        self.publish('pedidos360.orders', 'order.created', payload, event_id)
        self.publish('pedidos360.tasks', 'ticket.requested', payload, event_id)
        def duplicates_acknowledged():
            states = {domain: self.broker('/queues/%2F/pedidos360.' + domain) for domain in domains}
            return all(state.get('message_stats', {}).get('ack', 0) > before[domain]
                       and state.get('messages_unacknowledged', 0) == 0 for domain, state in states.items())
        wait_for(duplicates_acknowledged, 'duplicate message processing')
        assert len(self.results()) == 3
        self.note('duplicate event delivery does not duplicate stored domain effects')
        topic_event = self.event(orderId=901)
        self.publish('pedidos360.orders', 'order.updated', json.dumps(topic_event), topic_event['eventId'])
        wait_for(lambda: any(item['eventId'] == topic_event['eventId'] for item in self.results()), 'topic wildcard delivery')
        assert [item['domain'] for item in self.results() if item['eventId'] == topic_event['eventId']] == ['NOTIFICATION']
        self.publish('pedidos360.tasks', 'not.bound', '{}', routed=False)
        self.note('topic wildcard versus exact routing and unmatched direct routing')
        for domain in ['notifications', 'documents', 'tickets']:
            self.publish('', 'pedidos360.' + domain, '{malformed')
            def failed():
                return self.broker('/queues/%2F/pedidos360.' + domain + '.dlq').get('messages', 0) >= 1
            wait_for(failed, domain + ' DLQ')
            messages = self.broker('/queues/%2F/pedidos360.' + domain + '.dlq/get', method='POST',
                                   body={'count': 1, 'ackmode': 'ack_requeue_true', 'encoding': 'auto', 'truncate': 2000})
            assert messages[0]['payload'] == '{malformed'
            assert messages[0]['properties']['headers']['x-death'][0]['reason'] == 'rejected'
        self.note('actual malformed deliveries explicitly rejected into all three DLQs')
        self.check_retry_exhaustion()
        name = 'integration-' + secrets.token_hex(4)
        q, x = name + '-queue', name + '-exchange'
        self.http(admin, '/api/rabbitmq/queues', method='POST', body={'name': '', 'durable': True}, token=token, status=400)
        self.http(admin, '/api/rabbitmq/exchanges', method='POST', body={'name': x, 'type': 'INVALID', 'durable': True}, token=token, status=400)
        self.http(admin, '/api/rabbitmq/queues', method='POST', body={'name': q, 'durable': True}, token=token, status=201)
        assert self.broker('/queues/%2F/' + q)['durable']
        self.http(admin, '/api/rabbitmq/exchanges', method='POST', body={'name': x, 'type': 'DIRECT', 'durable': True}, token=token, status=201)
        assert self.broker('/exchanges/%2F/' + x)['type'] == 'direct'
        binding = {'exchange': x, 'queue': q, 'routingKey': 'integration.key'}
        self.http(admin, '/api/rabbitmq/bindings', method='POST', body=binding, token=token, status=201)
        assert any(item['routing_key'] == 'integration.key' for item in self.broker('/bindings/%2F/e/' + x + '/q/' + q))
        self.http(admin, '/api/rabbitmq/bindings', method='DELETE', body=binding, token=token, status=204)
        assert self.broker('/bindings/%2F/e/' + x + '/q/' + q) == []
        self.http(admin, '/api/rabbitmq/queues/' + q, method='DELETE', token=token, status=204)
        self.broker('/queues/%2F/' + q, status=404)
        self.http(admin, '/api/rabbitmq/exchanges/' + x, method='DELETE', token=token, status=204)
        self.broker('/exchanges/%2F/' + x, status=404)
        self.note('validated admin API creates/deletes queues, exchanges and bindings with broker readback')
        original = len(self.results())
        self.stop_application('worker-service')
        self.start_application('worker-service')
        assert len(self.results()) == original
        assert len(self.http(orders, '/api/pedidos', token=token)) == 1
        self.note('worker results persist across service restart')
        self.check_broker_recovery(token)
        print(json.dumps({'result': 'passed', 'checks': self.passed,
                          'cloud': 'not tested; loopback identity fixture is not Cognito'}, indent=2), flush=True)

    def check_retry_exhaustion(self):
        # Hold a real PostgreSQL lock. A bounded worker lock_timeout yields transient failures.
        process = subprocess.Popen(['docker', 'exec', '-i', self.database, 'psql', '-X', '-q', '-A', '-t',
                                    '-v', 'ON_ERROR_STOP=1', '-U', 'pedidos360', '-d', 'pedidos360_worker'],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            process.stdin.write("BEGIN; LOCK TABLE processing_result IN ACCESS EXCLUSIVE MODE; SELECT 'lock-held';\n")
            process.stdin.flush()
            assert process.stdout.readline().strip() == 'lock-held'
            event = self.event(orderId=902)
            self.publish('', 'pedidos360.tickets', json.dumps(event), event['eventId'])
            wait_for(lambda: self.broker('/queues/%2F/pedidos360.tickets.dlq').get('messages', 0) >= 2,
                     'bounded transient failure to ticket DLQ')
            log = self.logs['worker-service'].read_text()
            assert 'Consumer retry: domain=TICKET' in log, log[-10000:]
            assert 'category=transient_exhausted' in log, log[-10000:]
        finally:
            if process.poll() is None:
                process.stdin.write('ROLLBACK;\n\\q\n')
                process.stdin.flush()
                process.communicate(timeout=10)
        self.note('real transient database failures retry finitely then NACK to DLQ')

    def check_broker_recovery(self, token):
        self.compose('stop', 'rabbit2', 'rabbit1')
        try:
            order = self.http(self.base('orders-service'), '/api/pedidos', method='POST',
                              body={'cliente': 'Broker recovery', 'producto': 'Test item', 'cantidad': 1},
                              token=token, status=201)
            assert int(self.sql('pedidos360_orders', 'select count(*) from outbox where published_at is null')) == 2
        finally:
            self.compose('up', '-d', '--wait', '--wait-timeout', '180')
        wait_for(lambda: len([item for item in self.results() if item['orderId'] == order['id']]) == 3,
                 'pending outbox recovery after broker restart', timeout=100)
        assert self.sql('pedidos360_orders', 'select count(*) from outbox where published_at is null') == '0'
        self.note('orders survive broker outage and pending messages publish after cluster recovery')

    def serve_browser(self, image):
        config = {'authority': self.identity.issuer, 'clientId': self.identity.client_id,
                  'logoutDomain': self.identity.issuer, 'redirectUri': self.frontend_origin + '/callback',
                  'logoutRedirectUri': self.frontend_origin + '/',
                  'apiBaseUrl': self.base('orders-service'), 'workerApiBaseUrl': self.base('worker-service'),
                  'scopes': 'openid email pedidos/read pedidos/write'}
        path = Path(self.work.name) / 'public-frontend.json'
        path.write_text(json.dumps(config))
        self.run(['docker', 'run', '--detach', '--rm', '--name', self.frontend_container,
                  '--label', 'hermes.task=pedidos360-local-integration',
                  '--publish', '127.0.0.1:' + str(self.frontend_port) + ':80', '--volume',
                  str(path) + ':/usr/share/nginx/html/assets/config.json:ro', image])
        self.created_frontend = True
        wait_for(lambda: urllib.request.urlopen(self.frontend_origin + '/assets/config.json', timeout=5).close() is None,
                 'frontend runtime configuration')
        print('LOCAL_BROWSER_READY ' + self.frontend_origin + ' (test identity fixture, NOT Cognito)', flush=True)
        print('Press Ctrl+C to remove only this disposable integration environment.', flush=True)
        signal.pause()

    def remove_owned_container(self, name):
        owner = self.run(['docker', 'inspect', '--type', 'container', '--format',
                          '{{index .Config.Labels "hermes.task"}}', name], allow_missing=True)
        if owner is None:
            return
        if owner != 'pedidos360-local-integration':
            raise RuntimeError('Container ownership mismatch: ' + name)
        # A --rm container can exit between inspection and removal.
        self.run(['docker', 'rm', '--force', name], allow_missing=True)

    def close(self):
        failures = []
        def attempt(label, operation):
            try:
                operation()
                return True
            except Exception as error:
                failures.append(label + ': ' + str(error))
                return False
        if self.created_frontend and attempt('frontend cleanup', lambda: self.remove_owned_container(self.frontend_container)):
            self.created_frontend = False
        for module in self.processes:
            attempt(module + ' shutdown', lambda module=module: self.stop_application(module))
        if self.identity:
            attempt('identity fixture shutdown', self.identity.close)
        if self.created_database and attempt('database cleanup', lambda: self.remove_owned_container(self.database)):
            self.created_database = False
        if self.created_cluster and attempt('cluster cleanup', lambda: self.compose('down', '--volumes', '--remove-orphans')):
            self.created_cluster = False
        attempt('temporary material cleanup', self.work.cleanup)
        if failures:
            raise RuntimeError('Incomplete local cleanup: ' + '; '.join(failures))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serve', action='store_true', help='Keep an explicit local-only test environment for browser verification.')
    parser.add_argument('--frontend-image', default='pedidos360-front:ev2-local')
    parser.add_argument('--frontend-port', type=int, default=4200)
    arguments = parser.parse_args()
    def interrupted(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    checks = LocalChecks(frontend_port=arguments.frontend_port)
    try:
        checks.start()
        checks.check()
        if arguments.serve:
            checks.serve_browser(arguments.frontend_image)
    except KeyboardInterrupt:
        print('Stopping disposable local integration environment.', flush=True)
    finally:
        checks.close()
