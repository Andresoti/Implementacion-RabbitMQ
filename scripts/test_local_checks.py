"""Regression tests for safe, independent cleanup of disposable local resources."""
import subprocess
import unittest
from unittest.mock import Mock, patch

from check_local import LocalChecks


class IsolationTests(unittest.TestCase):
    def test_browser_origin_is_explicitly_loopback_only(self):
        checks = LocalChecks()
        self.addCleanup(checks.close)
        self.assertEqual(getattr(checks, 'frontend_origin', None), 'http://localhost:4200')

    def test_each_check_uses_isolated_broker_ports(self):
        checks = LocalChecks()
        self.addCleanup(checks.close)
        names = ['RABBITMQ_AMQP_PORT1', 'RABBITMQ_AMQP_PORT2',
                 'RABBITMQ_MANAGEMENT_PORT1', 'RABBITMQ_MANAGEMENT_PORT2']
        self.assertTrue(all(name in checks.env for name in names),
                        'Integration checks must not bind to an existing development broker')
        for name in names:
            self.assertGreater(int(checks.env[name]), 0)


class CleanupTests(unittest.TestCase):
    def checks(self):
        checks = object.__new__(LocalChecks)
        checks.env = {}
        checks.frontend_container = 'owned-test-frontend'
        checks.database = 'owned-test-database'
        checks.created_frontend = True
        checks.created_database = True
        checks.created_cluster = True
        checks.processes = {'orders-service': Mock()}
        checks.stop_application = Mock()
        checks.identity = Mock()
        checks.work = Mock()
        checks.compose = Mock(return_value='')
        return checks

    def docker(self, frontend='missing', database='owned'):
        def result(args, **kwargs):
            if args[:2] == ['docker', 'inspect']:
                state = frontend if args[-1] == 'owned-test-frontend' else database
                if state == 'missing':
                    return subprocess.CompletedProcess(args, 1, '', 'Error: No such object: already removed')
                if state == 'daemon-error':
                    return subprocess.CompletedProcess(args, 1, '', 'Cannot connect to the Docker daemon')
                owner = 'pedidos360-local-integration' if state == 'owned' else 'another-task'
                return subprocess.CompletedProcess(args, 0, owner + '\n', '')
            return subprocess.CompletedProcess(args, 0, '', '')
        return result

    def assert_remaining_cleanup(self, checks):
        checks.stop_application.assert_called_once_with('orders-service')
        checks.identity.close.assert_called_once()
        checks.compose.assert_called_once_with('down', '--volumes', '--remove-orphans')
        checks.work.cleanup.assert_called_once()

    @patch('check_local.subprocess.run')
    def test_auto_removed_frontend_does_not_abort_other_cleanup(self, run):
        run.side_effect = self.docker()
        checks = self.checks()
        checks.close()
        self.assert_remaining_cleanup(checks)
        self.assertFalse(checks.created_frontend)
        self.assertFalse(checks.created_database)
        self.assertFalse(checks.created_cluster)
        removed = [call.args[0][-1] for call in run.call_args_list if call.args[0][:2] == ['docker', 'rm']]
        self.assertEqual(removed, ['owned-test-database'])

    @patch('check_local.subprocess.run')
    def test_auto_removed_database_is_already_clean(self, run):
        run.side_effect = self.docker(frontend='owned', database='missing')
        checks = self.checks()
        checks.close()
        self.assert_remaining_cleanup(checks)

    @patch('check_local.subprocess.run')
    def test_wrong_owner_is_never_removed_and_other_cleanup_continues(self, run):
        run.side_effect = self.docker(frontend='wrong-owner')
        checks = self.checks()
        with self.assertRaisesRegex(RuntimeError, 'ownership'):
            checks.close()
        self.assert_remaining_cleanup(checks)
        removed = [call.args[0][-1] for call in run.call_args_list if call.args[0][:2] == ['docker', 'rm']]
        self.assertNotIn('owned-test-frontend', removed)

    @patch('check_local.subprocess.run')
    def test_daemon_error_is_reported_after_other_cleanup_attempts(self, run):
        run.side_effect = self.docker(frontend='daemon-error')
        checks = self.checks()
        with self.assertRaises(RuntimeError):
            checks.close()
        self.assert_remaining_cleanup(checks)


if __name__ == '__main__':
    unittest.main()
