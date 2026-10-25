"""Exercise the actual two-node Compose cluster using disposable test resources."""
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import urllib.request
from check_local import free_port

ROOT = Path(__file__).resolve().parents[1]
project = 'pedidos360-cluster-check-' + secrets.token_hex(4)
env = os.environ.copy()
env.update(RABBITMQ_USERNAME='local-cluster-check', RABBITMQ_PASSWORD=secrets.token_urlsafe(24),
           RABBITMQ_ERLANG_COOKIE=secrets.token_hex(24))
for name in ['RABBITMQ_AMQP_PORT1', 'RABBITMQ_AMQP_PORT2',
             'RABBITMQ_MANAGEMENT_PORT1', 'RABBITMQ_MANAGEMENT_PORT2']:
    env[name] = str(free_port())
command = ['docker', 'compose', '--project-name', project, '-f', str(ROOT / 'compose.rabbitmq.yaml')]
created = False

def compose(*args):
    result = subprocess.run(command + list(args), env=env, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr[-4000:] + result.stdout[-4000:])
    return result.stdout

try:
    compose('config', '--quiet')
    created = True
    compose('up', '-d', '--wait', '--wait-timeout', '180')
    result = compose('exec', '-T', 'rabbit1', 'rabbitmqctl', 'cluster_status', '--formatter=json')
    status = json.loads(result)
    running = sorted(status.get('running_nodes', []))
    assert running == ['rabbit@rabbit1', 'rabbit@rabbit2'], status
    credentials = base64.b64encode((env['RABBITMQ_USERNAME'] + ':' + env['RABBITMQ_PASSWORD']).encode()).decode()
    headers = {'Authorization': 'Basic ' + credentials}
    with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:' + env['RABBITMQ_MANAGEMENT_PORT1'] + '/api/nodes', headers=headers), timeout=10) as response:
        nodes = json.load(response)
    assert sorted(node['name'] for node in nodes if node['running']) == running, nodes
    print(json.dumps({'result': 'passed', 'running_cluster_nodes': running,
                      'management_node_count': len(nodes), 'project': project,
                      'resources': 'disposable local test cluster; no AWS'}, indent=2))
finally:
    if created:
        cleanup = subprocess.run(command + ['down', '--volumes', '--remove-orphans'], env=env, capture_output=True, text=True)
        if cleanup.returncode:
            print('Cleanup failed for owned test project ' + project + ': ' + cleanup.stderr[-1000:])
